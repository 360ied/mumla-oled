/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;

import java.io.InputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import se.lublin.mumla.Settings;

/**
 * Implementation of ImageGetter designed for Mumble MOTDs and messages.
 * Reads base64-embedded images synchronously and fetches external image URLs asynchronously
 * on background worker threads without blocking the main UI thread. Caches loaded bitmaps.
 * Created by andrew on 07/02/14.
 */
public class MumbleImageGetter implements Html.ImageGetter {
    private static final String TAG = MumbleImageGetter.class.getName();

    /** The maximum image size in bytes to load. */
    private static final int MAX_LENGTH = 64000;

    /** Estimated total horizontal padding/margin around chat message text in dp. */
    private static final int HORIZONTAL_PADDING_DP = 48;

    /**
     * Timeout for external image HTTP connection and stream reads in milliseconds.
     * Safe to keep at 15000ms as requests are performed asynchronously in background threads.
     */
    private static final int NETWORK_TIMEOUT_MS = 15000;

    /**
     * Callback interface invoked on the main UI thread when a background image finishes loading.
     */
    public interface OnImageLoadedListener {
        void onImageLoaded();
    }

    private final Context mContext;
    private final Settings mSettings;
    private final Map<String, Bitmap> mBitmapCache;
    private final Set<String> mPendingDownloads;
    private final Handler mMainHandler;
    private final ExecutorService mExecutor;
    private OnImageLoadedListener mListener;

    public MumbleImageGetter(Context context) {
        this(context, null);
    }

    public MumbleImageGetter(Context context, OnImageLoadedListener listener) {
        mContext = context;
        mSettings = Settings.getInstance(context);
        mListener = listener;
        mBitmapCache = new HashMap<>();
        mPendingDownloads = Collections.synchronizedSet(new HashSet<>());
        mMainHandler = new Handler(Looper.getMainLooper());
        mExecutor = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private int mCount = 1;

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "MumbleImageLoader-" + mCount++);
                t.setDaemon(true);
                return t;
            }
        });
    }

    public void setOnImageLoadedListener(OnImageLoadedListener listener) {
        mListener = listener;
    }

    @Override
    public Drawable getDrawable(String source) {
        String decodedSource; // Decode from URL encoding
        try {
            // Preserve literal '+' characters in raw base64 data URIs before URLDecoder converts them to spaces
            String safeSource = (source != null && source.startsWith("data:image"))
                    ? source.replace("+", "%2B") : source;
            decodedSource = safeSource != null ? URLDecoder.decode(safeSource, "UTF-8") : null;
        } catch (UnsupportedEncodingException e) {
            Log.w(TAG, "exception when decoding source: " + e.toString());
            return null;
        }

        if (decodedSource == null) return null;

        Bitmap bitmap = mBitmapCache.get(decodedSource);
        if (bitmap == null) {
            if (decodedSource.startsWith("data:image")) {
                try {
                    int commaIndex = decodedSource.indexOf(',');
                    if (commaIndex != -1 && commaIndex < decodedSource.length() - 1) {
                        bitmap = getBase64Image(decodedSource.substring(commaIndex + 1));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "exception when decoding base64 image: " + t.toString());
                    return null;
                }
                if (bitmap != null) {
                    mBitmapCache.put(decodedSource, bitmap);
                }
            } else if (mSettings.shouldLoadExternalImages()) {
                fetchURLImageAsync(decodedSource);
                return null;
            }
        }

        if (bitmap == null) return null;

        return createDrawable(bitmap);
    }

    private Drawable createDrawable(Bitmap bitmap) {
        BitmapDrawable drawable = new BitmapDrawable(mContext.getResources(), bitmap);
        DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();

        int intrinsicWidth = drawable.getIntrinsicWidth();
        int intrinsicHeight = drawable.getIntrinsicHeight();
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
            return null;
        }

        int horizontalPaddingPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, HORIZONTAL_PADDING_DP, metrics);
        int maxWidth = Math.max(metrics.widthPixels - horizontalPaddingPx, 1);

        int targetWidth = intrinsicWidth;
        int targetHeight = intrinsicHeight;

        if (targetWidth > maxWidth) {
            targetWidth = maxWidth;
            targetHeight = Math.max(1, (int) ((float) intrinsicHeight * maxWidth / (float) intrinsicWidth));
        }

        drawable.setBounds(0, 0, targetWidth, targetHeight);
        return drawable;
    }

    private void fetchURLImageAsync(final String source) {
        if (!mPendingDownloads.add(source)) {
            return; // Already in flight
        }

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap bitmap = fetchURLImage(source);
                    if (bitmap != null) {
                        mBitmapCache.put(source, bitmap);
                        mMainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (mListener != null) {
                                    mListener.onImageLoaded();
                                }
                            }
                        });
                    }
                } finally {
                    mPendingDownloads.remove(source);
                }
            }
        });
    }

    private Bitmap getBase64Image(String base64) throws IllegalArgumentException {
        byte[] src = Base64.decode(base64, Base64.DEFAULT);
        return BitmapFactory.decodeByteArray(src, 0, src.length);
    }

    private Bitmap fetchURLImage(String source) {
        try {
            URL url = new URL(source);
            URLConnection conn = url.openConnection();
            conn.setConnectTimeout(NETWORK_TIMEOUT_MS);
            conn.setReadTimeout(NETWORK_TIMEOUT_MS);
            if (conn.getContentLength() > MAX_LENGTH) return null;
            try (InputStream is = conn.getInputStream()) {
                return BitmapFactory.decodeStream(is);
            }
        } catch (IOException e) {
            Log.w(TAG, "failed to load URL image: " + e.toString());
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "OOM decoding URL image: " + e.toString());
        }
        return null;
    }
}

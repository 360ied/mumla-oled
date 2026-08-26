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
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
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
    private static final String TAG = "MumbleImageGetter";

    /** The maximum image size in bytes to load. */
    private static final int MAX_LENGTH = 10 * 1024 * 1024;

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
    private final LruCache<String, Bitmap> mBitmapCache;
    private final Set<String> mPendingDownloads;
    private final Set<String> mFailedDownloads;
    private final Handler mMainHandler;
    private final ExecutorService mExecutor;
    private OnImageLoadedListener mListener;

    private final Runnable mNotifyRunnable = new Runnable() {
        @Override
        public void run() {
            if (mListener != null) {
                mListener.onImageLoaded();
            }
        }
    };

    public MumbleImageGetter(Context context) {
        this(context, null);
    }

    public MumbleImageGetter(Context context, OnImageLoadedListener listener) {
        mContext = context.getApplicationContext();
        mSettings = Settings.getInstance(mContext);
        mListener = listener;

        // Allocate up to 1/8th of available runtime memory for the LRU bitmap cache (in KB)
        int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSizeKb = Math.max(maxMemoryKb / 8, 1024); // at least 1MB
        mBitmapCache = new LruCache<String, Bitmap>(cacheSizeKb) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };

        mPendingDownloads = Collections.synchronizedSet(new HashSet<>());
        mFailedDownloads = Collections.synchronizedSet(new HashSet<>());
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

    /**
     * Shuts down the background executor and clears callbacks to prevent memory/thread leaks.
     */
    public void shutdown() {
        mListener = null;
        mMainHandler.removeCallbacksAndMessages(null);
        mExecutor.shutdownNow();
    }

    @Override
    public Drawable getDrawable(String source) {
        if (source == null || source.isEmpty()) {
            return null;
        }

        if (source.regionMatches(true, 0, "data:image", 0, 10)) {
            Bitmap bitmap = mBitmapCache.get(source);
            if (bitmap != null) {
                return createDrawable(bitmap);
            }
            try {
                int commaIndex = source.indexOf(',');
                if (commaIndex != -1 && commaIndex < source.length() - 1) {
                    String base64Data = source.substring(commaIndex + 1);
                    if (base64Data.endsWith("\"") || base64Data.endsWith("'")) {
                        base64Data = base64Data.substring(0, base64Data.length() - 1);
                    }
                    bitmap = getBase64Image(base64Data);
                    if (bitmap != null) {
                        mBitmapCache.put(source, bitmap);
                        return createDrawable(bitmap);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "exception when decoding base64 image: " + t.toString());
            }
            return null;
        }

        // Check cache for downloaded HTTP/HTTPS image
        Bitmap bitmap = mBitmapCache.get(source);
        if (bitmap != null) {
            return createDrawable(bitmap);
        }

        // Avoid re-fetching failed URLs
        if (mFailedDownloads.contains(source)) {
            return null;
        }

        if (mSettings.shouldLoadExternalImages()) {
            fetchURLImageAsync(source);
        }

        return null;
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
        if (mFailedDownloads.contains(source)) {
            return;
        }
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
                        notifyImageLoaded();
                    } else {
                        mFailedDownloads.add(source);
                    }
                } finally {
                    mPendingDownloads.remove(source);
                }
            }
        });
    }

    private void notifyImageLoaded() {
        mMainHandler.removeCallbacks(mNotifyRunnable);
        mMainHandler.post(mNotifyRunnable);
    }

    public static String percentDecode(String s) {
        if (s == null || s.indexOf('%') == -1) {
            return s;
        }
        try {
            String decoded = Uri.decode(s);
            if (decoded != null) {
                return decoded;
            }
        } catch (Throwable ignored) {
        }
        // Fallback for JVM unit tests or unmocked stubs
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    int code = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                    sb.append((char) code);
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static byte[] decodeBase64Bytes(String base64) throws IllegalArgumentException {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        String decodedBase64 = percentDecode(base64);
        byte[] src;
        try {
            src = Base64.decode(decodedBase64, Base64.DEFAULT);
            if (src == null) {
                src = java.util.Base64.getMimeDecoder().decode(decodedBase64);
            }
        } catch (Throwable t) {
            src = java.util.Base64.getMimeDecoder().decode(decodedBase64);
        }
        if (src == null || src.length == 0 || src.length > MAX_LENGTH) {
            return null;
        }
        return src;
    }

    private Bitmap getBase64Image(String base64) throws IllegalArgumentException {
        byte[] src = decodeBase64Bytes(base64);
        if (src == null) {
            return null;
        }
        try {
            return BitmapFactory.decodeByteArray(src, 0, src.length);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "OOM decoding base64 image: " + e.toString());
            return null;
        }
    }

    private Bitmap fetchURLImage(String source) {
        HttpURLConnection httpConn = null;
        try {
            URL url = new URL(source);
            String protocol = url.getProtocol();
            if (protocol == null || (!protocol.equalsIgnoreCase("http") && !protocol.equalsIgnoreCase("https"))) {
                Log.w(TAG, "Refusing to load image with non-HTTP protocol: " + protocol);
                return null;
            }

            URLConnection conn = url.openConnection();
            if (conn instanceof HttpURLConnection) {
                httpConn = (HttpURLConnection) conn;
                httpConn.setInstanceFollowRedirects(true);
            }
            conn.setConnectTimeout(NETWORK_TIMEOUT_MS);
            conn.setReadTimeout(NETWORK_TIMEOUT_MS);

            int contentLength = conn.getContentLength();
            if (contentLength > MAX_LENGTH) {
                return null;
            }

            try (InputStream is = conn.getInputStream()) {
                byte[] data = readStreamWithLimit(is, MAX_LENGTH);
                if (data == null || data.length == 0) {
                    return null;
                }
                return BitmapFactory.decodeByteArray(data, 0, data.length);
            }
        } catch (IOException e) {
            Log.w(TAG, "failed to load URL image: " + e.toString());
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "OOM decoding URL image: " + e.toString());
        } finally {
            if (httpConn != null) {
                httpConn.disconnect();
            }
        }
        return null;
    }

    private static byte[] readStreamWithLimit(InputStream is, int maxBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int totalRead = 0;
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            totalRead += bytesRead;
            if (totalRead > maxBytes) {
                return null; // Exceeded maximum allowable byte size
            }
            baos.write(buffer, 0, bytesRead);
        }
        return baos.toByteArray();
    }
}

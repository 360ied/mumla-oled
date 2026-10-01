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
import android.annotation.SuppressLint;
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
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.net.ssl.HttpsURLConnection;

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

    /**
     * Receive-side decode caps for chat images. The send path already caps
     * outgoing images at 1600 px, so 4096 px / ~16 MP comfortably covers
     * interop without handing the decoder an unbounded allocation.
     */
    public static final int CHAT_MAX_DIMENSION = 4096;
    public static final int CHAT_MAX_PIXELS = 16 * 1024 * 1024;

    /**
     * Absolute rejection ceiling applied before the sampled decode. Anything
     * past this is a compressed bomb, never a photo: refuse outright instead
     * of downsampling.
     */
    public static final int ABSOLUTE_MAX_DIMENSION = 32768;
    public static final long ABSOLUTE_MAX_PIXELS = 64L * 1024 * 1024;

    /** Estimated total horizontal padding/margin around chat message text in dp. */
    private static final int HORIZONTAL_PADDING_DP = 48;

    /** Maximum dimension in dp for an image to be treated as an icon/emoji rather than a photo. */
    public static final int ICON_MAX_SIZE_DP = 96;

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
    // android.util.LruCache synchronizes get/put internally, so UI-thread
    // reads (getDrawable) racing background-thread writes
    // (fetchURLImageAsync) are memory-safe. Check-then-act sequences
    // (get-then-put) are not atomic, but the worst case is a redundant
    // decode, never corruption.
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
                    String base64Data = source.substring(commaIndex + 1).trim();
                    while (base64Data.startsWith("\"") || base64Data.startsWith("'")) {
                        base64Data = base64Data.substring(1).trim();
                    }
                    while (base64Data.endsWith("\"") || base64Data.endsWith("'")) {
                        base64Data = base64Data.substring(0, base64Data.length() - 1).trim();
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

    public static class ImageBounds {
        public final int width;
        public final int height;

        public ImageBounds(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ImageBounds that = (ImageBounds) o;
            return width == that.width && height == that.height;
        }

        @Override
        public int hashCode() {
            return 31 * width + height;
        }

        @Override
        public String toString() {
            return "ImageBounds{" + width + "x" + height + "}";
        }
    }

    /**
     * Calculates the display bounds for an image so that it fits the screen width (accounting for padding)
     * while preserving aspect ratio, and capping maximum height to prevent tall images from dominating the viewport.
     * Small icons/emojis are scaled according to display density rather than stretched to the full screen width.
     */
    public static ImageBounds calculateImageBounds(
            int intrinsicWidth,
            int intrinsicHeight,
            int displayWidth,
            int displayHeight,
            int horizontalPaddingPx) {
        return calculateImageBounds(intrinsicWidth, intrinsicHeight, displayWidth, displayHeight, horizontalPaddingPx, 1.0f);
    }

    public static ImageBounds calculateImageBounds(
            int intrinsicWidth,
            int intrinsicHeight,
            int displayWidth,
            int displayHeight,
            int horizontalPaddingPx,
            float density) {
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            return null;
        }

        float safeDensity = (!Float.isNaN(density) && Float.isFinite(density) && density > 0f) ? density : 1.0f;
        int safePadding = Math.max(0, horizontalPaddingPx);
        int maxWidth = Math.max(displayWidth - safePadding, 1);
        int maxHeight = Math.max((int) (displayHeight * 0.65f), 1);

        int targetWidth;
        int targetHeight;

        float widthDp = intrinsicWidth / safeDensity;
        float heightDp = intrinsicHeight / safeDensity;

        if (widthDp <= ICON_MAX_SIZE_DP && heightDp <= ICON_MAX_SIZE_DP) {
            // Small icon / emoji: scale according to display density
            targetWidth = Math.min(maxWidth, Math.max(1, Math.round(intrinsicWidth * safeDensity)));
            targetHeight = Math.max(1, Math.round((float) intrinsicHeight * targetWidth / (float) intrinsicWidth));
        } else {
            // Photo / chat image: expand to fill chat width
            targetWidth = maxWidth;
            targetHeight = Math.max(1, Math.round((float) intrinsicHeight * maxWidth / (float) intrinsicWidth));
        }

        if (targetHeight > maxHeight) {
            targetHeight = maxHeight;
            targetWidth = Math.max(1, Math.round((float) intrinsicWidth * maxHeight / (float) intrinsicHeight));
        }

        return new ImageBounds(targetWidth, targetHeight);
    }

    private Drawable createDrawable(Bitmap bitmap) {
        BitmapDrawable drawable = new BitmapDrawable(mContext.getResources(), bitmap);
        DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();

        int intrinsicWidth = bitmap.getWidth();
        int intrinsicHeight = bitmap.getHeight();
        int horizontalPaddingPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, HORIZONTAL_PADDING_DP, metrics);

        ImageBounds bounds = calculateImageBounds(
                intrinsicWidth,
                intrinsicHeight,
                metrics.widthPixels,
                metrics.heightPixels,
                horizontalPaddingPx,
                metrics.density);

        if (bounds == null) {
            return null;
        }

        drawable.setBounds(0, 0, bounds.width, bounds.height);
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
                int d1 = Character.digit(s.charAt(i + 1), 16);
                int d2 = Character.digit(s.charAt(i + 2), 16);
                if (d1 != -1 && d2 != -1) {
                    sb.append((char) ((d1 << 4) | d2));
                    i += 2;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // java.util.Base64 requires API 26; on older devices the reference would
    // raise NoClassDefFoundError, which the catch (Throwable) blocks handle
    // deliberately. Local unit tests also exercise this path on the desktop
    // JVM, where android.util.Base64 is stubbed out — so no SDK_INT gate.
    @SuppressLint("NewApi")
    public static byte[] decodeBase64Bytes(String base64) throws IllegalArgumentException {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        String decodedBase64 = percentDecode(base64);
        byte[] src;
        try {
            src = Base64.decode(decodedBase64, Base64.DEFAULT);
            if (src == null) {
                try {
                    src = java.util.Base64.getMimeDecoder().decode(decodedBase64);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        } catch (Throwable t) {
            try {
                src = java.util.Base64.getMimeDecoder().decode(decodedBase64);
            } catch (Throwable ignored) {
                return null;
            }
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
        return decodeBoundedImage(src, CHAT_MAX_DIMENSION, CHAT_MAX_PIXELS);
    }

    /**
     * Pure power-of-two sampler: smallest sample size keeping both sides
     * within maxDim and total pixels within maxPixels. Invalid inputs fail
     * closed (sample size 1 lets the absolute-rejection gate decide).
     */
    public static int calculateInSampleSize(
            int width, int height, int maxDim, long maxPixels) {
        if (width <= 0 || height <= 0 || maxDim <= 0 || maxPixels <= 0) {
            return 1;
        }
        int sampleSize = 1;
        while (width / sampleSize > maxDim
                || height / sampleSize > maxDim
                || (long) (width / sampleSize) * (height / sampleSize) > maxPixels) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    /** True when raw dimensions exceed the absolute rejection ceiling. */
    public static boolean isOversize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return true;
        }
        return width > ABSOLUTE_MAX_DIMENSION
                || height > ABSOLUTE_MAX_DIMENSION
                || (long) width * height > ABSOLUTE_MAX_PIXELS;
    }

    /**
     * Two-pass bounded decode: reads dimensions first via
     * inJustDecodeBounds, rejects bombs past the absolute ceiling, then
     * downsamples to the chat caps (4096 px / ~16 MP) before the full
     * decode. Synchronous by design: getDrawable runs base64 decode on the
     * UI thread and fetchURLImage already runs on mExecutor, so no
     * signature change or pop-in is needed. Returns null on rejection.
     */
    public static Bitmap decodeBoundedImage(byte[] data, int maxDim, long maxPixels) {
        if (data == null || data.length == 0 || data.length > MAX_LENGTH) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int width = bounds.outWidth;
            int height = bounds.outHeight;
            if (width <= 0 || height <= 0 || isOversize(width, height)) {
                Log.w(TAG, "rejecting oversize image: " + width + "x" + height);
                return null;
            }
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = calculateInSampleSize(width, height, maxDim, maxPixels);
            return BitmapFactory.decodeByteArray(data, 0, data.length, decode);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "OOM decoding bounded image: " + e.toString());
            return null;
        }
    }

    private Bitmap fetchURLImage(String source) {
        try {
            URL url = new URL(source);
            int redirectsFollowed = 0;
            while (true) {
                FetchResult result = fetchOneUrl(url);
                if (!result.redirect) {
                    return result.bitmap;
                }
                if (!SsrfHostPolicy.shouldFollowRedirect(
                        redirectsFollowed, result.status, result.location)) {
                    Log.w(TAG, "stopping redirect chain");
                    return null;
                }
                redirectsFollowed++;
                url = new URL(url, result.location);
            }
        } catch (IOException e) {
            Log.w(TAG, "failed to load URL image: " + e.toString());
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "OOM decoding URL image: " + e.toString());
        }
        return null;
    }

    /** Outcome of one fetch hop: either a decoded bitmap or a redirect. */
    private static final class FetchResult {
        final Bitmap bitmap;
        final boolean redirect;
        final int status;
        final String location;

        FetchResult(Bitmap bitmap) {
            this.bitmap = bitmap;
            this.redirect = false;
            this.status = -1;
            this.location = null;
        }

        FetchResult(int status, String location) {
            this.bitmap = null;
            this.redirect = true;
            this.status = status;
            this.location = location;
        }
    }

    /**
     * Fetches a single URL without following redirects. Every hop
     * re-applies the scheme, userinfo, and SSRF host checks, so a
     * redirect to a private IP or a non-http(s) scheme is refused.
     */
    private FetchResult fetchOneUrl(URL url) throws IOException {
        if (!isSchemeAllowed(url) || SsrfHostPolicy.hasUserinfo(url)) {
            Log.w(TAG, "Refusing to load image with disallowed URL");
            return new FetchResult(null);
        }
        InetAddress[] checked = resolveAndCheck(url); // null = blocked (fail closed), per-hop
        if (checked == null) {
            Log.w(TAG, "Refusing to load image from blocked host");
            return new FetchResult(null);
        }
        IOException lastFailure = null;
        for (InetAddress addr : checked) {
            try {
                return fetchPinned(url, addr);
            } catch (IOException e) {
                lastFailure = e; // try the next checked address
            }
        }
        throw lastFailure; // all checked addresses failed; loop treats as fetch failure
    }

    /**
     * Resolve-once SSRF check: literals are classified without DNS, hostnames are
     * resolved and refused when any address is blocked. Returns the checked
     * addresses, or null when the host is refused (fail closed). Every redirect
     * hop re-resolves and re-pins through fetchOneUrl.
     */
    private static InetAddress[] resolveAndCheck(URL url) {
        String host = url.getHost();
        if (host == null || host.isEmpty()) {
            return null;
        }
        if (SsrfHostPolicy.looksLikeIpLiteral(host)) {
            if (SsrfHostPolicy.isLiteralBlocked(host)) {
                return null;
            }
            try {
                return new InetAddress[]{
                        InetAddress.getByName(SsrfHostPolicy.normalizeHost(host))};
            } catch (UnknownHostException e) {
                return null;
            }
        }
        try {
            InetAddress[] addresses =
                    InetAddress.getAllByName(SsrfHostPolicy.normalizeHost(host));
            if (SsrfHostPolicy.isAnyAddressBlocked(addresses)) {
                return null;
            }
            return addresses;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Fetches one hop through an already-checked address: the connection URL carries
     * the IP literal (no second DNS lookup) while the Host header — and, for https,
     * SNI plus the platform hostname verifier — keep presenting the original hostname.
     */
    private FetchResult fetchPinned(URL url, InetAddress addr) throws IOException {
        URLConnection conn = SsrfHostPolicy.buildPinnedUrl(url, addr).openConnection();
        if (!(conn instanceof HttpURLConnection)) {
            return new FetchResult(null);
        }
        HttpURLConnection httpConn = (HttpURLConnection) conn;
        try {
            httpConn.setInstanceFollowRedirects(false);
            httpConn.setConnectTimeout(NETWORK_TIMEOUT_MS);
            httpConn.setReadTimeout(NETWORK_TIMEOUT_MS);
            httpConn.setRequestProperty("Host", SsrfHostPolicy.hostHeaderValue(url));
            if (httpConn instanceof HttpsURLConnection) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) httpConn;
                httpsConn.setSSLSocketFactory(new PinnedTlsSocketFactory(url.getHost()));
                httpsConn.setHostnameVerifier(
                        PinnedTlsSocketFactory.verifierFor(url.getHost()));
            }
            int status = httpConn.getResponseCode();
            if (SsrfHostPolicy.isRedirect(status)) {
                return new FetchResult(status, httpConn.getHeaderField("Location"));
            }

            int contentLength = httpConn.getContentLength();
            if (contentLength > MAX_LENGTH) {
                return new FetchResult(null);
            }

            try (InputStream is = httpConn.getInputStream()) {
                byte[] data = readStreamWithLimit(is, MAX_LENGTH);
                if (data == null || data.length == 0) {
                    return new FetchResult(null);
                }
                return new FetchResult(
                        decodeBoundedImage(data, CHAT_MAX_DIMENSION, CHAT_MAX_PIXELS));
            }
        } finally {
            httpConn.disconnect();
        }
    }

    static boolean isSchemeAllowed(URL url) {
        if (url == null) {
            return false;
        }
        String protocol = url.getProtocol();
        return protocol != null
                && (protocol.equalsIgnoreCase("http") || protocol.equalsIgnoreCase("https"));
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

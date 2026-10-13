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

package se.lublin.mumla.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Base64
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt
import se.lublin.mumla.Settings

/**
 * Implementation of ImageGetter designed for Mumble MOTDs and messages.
 * Reads base64-embedded images synchronously and fetches external image URLs asynchronously
 * on background worker threads without blocking the main UI thread. Caches loaded bitmaps.
 * Created by andrew on 07/02/14.
 */
class MumbleImageGetter : Html.ImageGetter {
    /**
     * Callback interface invoked on the main UI thread when a background image finishes loading.
     */
    fun interface OnImageLoadedListener {
        fun onImageLoaded()
    }

    private val mContext: Context
    private val mSettings: Settings
    // android.util.LruCache synchronizes get/put internally, so UI-thread
    // reads (getDrawable) racing background-thread writes
    // (fetchURLImageAsync) are memory-safe. Check-then-act sequences
    // (get-then-put) are not atomic, but the worst case is a redundant
    // decode, never corruption.
    private val mBitmapCache: LruCache<String, Bitmap>
    private val mPendingDownloads: MutableSet<String>
    private val mFailedDownloads: MutableSet<String>
    private val mMainHandler: Handler
    private val mExecutor: ExecutorService
    private var mListener: OnImageLoadedListener?

    private val mNotifyRunnable: Runnable = object : Runnable {
        override fun run() {
            val listener = mListener
            if (listener != null) {
                listener.onImageLoaded()
            }
        }
    }

    constructor(context: Context) : this(context, null)

    constructor(context: Context, listener: OnImageLoadedListener?) {
        mContext = context.applicationContext
        mSettings = Settings.getInstance(mContext)
        mListener = listener

        // Allocate up to 1/8th of available runtime memory for the LRU bitmap cache (in KB)
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = maxOf(maxMemoryKb / 8, 1024) // at least 1MB
        mBitmapCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: String?, bitmap: Bitmap?): Int {
                // NPE-exact: the original dereferenced bitmap unconditionally.
                val b = bitmap ?: throw NullPointerException("bitmap")
                // Floor at 1 KB: sub-KB bitmaps must still consume quota or
                // unbounded tiny emotes evade accounting.
                return maxOf(1, b.byteCount / 1024)
            }
        }

        mPendingDownloads = Collections.synchronizedSet(HashSet())
        mFailedDownloads = Collections.synchronizedSet(HashSet())
        mMainHandler = Handler(Looper.getMainLooper())
        mExecutor = Executors.newFixedThreadPool(2, object : ThreadFactory {
            private var mCount = 1

            override fun newThread(r: Runnable): Thread {
                val t = Thread(r, "MumbleImageLoader-" + mCount++)
                t.isDaemon = true
                return t
            }
        })
    }

    fun setOnImageLoadedListener(listener: OnImageLoadedListener?) {
        mListener = listener
    }

    /**
     * Shuts down the background executor and clears callbacks to prevent memory/thread leaks.
     * Also clears the failed-download set: it is keyed by URL within this
     * adapter's lifetime, and a server-controlled stream of unique URLs
     * would otherwise grow it without bound.
     */
    fun shutdown() {
        mListener = null
        mMainHandler.removeCallbacksAndMessages(null)
        mPendingDownloads.clear()
        mFailedDownloads.clear()
        mExecutor.shutdownNow()
    }

    override fun getDrawable(source: String?): Drawable? {
        if (source == null || source.isEmpty()) {
            return null
        }

        if (source.regionMatches(0, "data:image", 0, 10, ignoreCase = true)) {
            var bitmap = mBitmapCache.get(source)
            if (bitmap != null) {
                return createDrawable(bitmap)
            }
            try {
                val commaIndex = source.indexOf(',')
                if (commaIndex != -1 && commaIndex < source.length - 1) {
                    var base64Data = source.substring(commaIndex + 1).trim()
                    while (base64Data.startsWith("\"") || base64Data.startsWith("'")) {
                        base64Data = base64Data.substring(1).trim()
                    }
                    while (base64Data.endsWith("\"") || base64Data.endsWith("'")) {
                        base64Data = base64Data.substring(0, base64Data.length - 1).trim()
                    }
                    bitmap = getBase64Image(base64Data)
                    if (bitmap != null) {
                        mBitmapCache.put(source, bitmap)
                        return createDrawable(bitmap)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "exception when decoding base64 image: $t")
            }
            return null
        }

        // Check cache for downloaded HTTP/HTTPS image
        val bitmap = mBitmapCache.get(source)
        if (bitmap != null) {
            return createDrawable(bitmap)
        }

        // Avoid re-fetching failed URLs
        if (mFailedDownloads.contains(source)) {
            return null
        }

        if (mSettings.shouldLoadExternalImages) {
            fetchURLImageAsync(source)
        }

        return null
    }

    class ImageBounds(@JvmField val width: Int, @JvmField val height: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            other as ImageBounds
            return width == other.width && height == other.height
        }

        override fun hashCode(): Int {
            return 31 * width + height
        }

        override fun toString(): String {
            return "ImageBounds{" + width + "x" + height + "}"
        }
    }

    private fun createDrawable(bitmap: Bitmap): Drawable? {
        val drawable = BitmapDrawable(mContext.resources, bitmap)
        val metrics = mContext.resources.displayMetrics

        val intrinsicWidth = bitmap.width
        val intrinsicHeight = bitmap.height
        val horizontalPaddingPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, HORIZONTAL_PADDING_DP.toFloat(), metrics
        ).toInt()

        val bounds = calculateImageBounds(
            intrinsicWidth,
            intrinsicHeight,
            metrics.widthPixels,
            metrics.heightPixels,
            horizontalPaddingPx,
            metrics.density
        ) ?: return null

        drawable.setBounds(0, 0, bounds.width, bounds.height)
        return drawable
    }

    private fun fetchURLImageAsync(source: String) {
        if (mFailedDownloads.contains(source)) {
            return
        }
        if (!mPendingDownloads.add(source)) {
            return // Already in flight
        }

        // shutdown() (fragment teardown) vs a lingering TextView drawable
        // request race: execute() after shutdownNow() throws
        // RejectedExecutionException on the UI thread. Guard first, catch
        // anyway (shutdown can land between the check and the submit).
        if (mExecutor.isShutdown) {
            mPendingDownloads.remove(source)
            return
        }
        try {
            mExecutor.execute {
                try {
                    val bitmap = fetchURLImage(source)
                    if (bitmap != null) {
                        mBitmapCache.put(source, bitmap)
                        notifyImageLoaded()
                    } else {
                        mFailedDownloads.add(source)
                    }
                } finally {
                    mPendingDownloads.remove(source)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            mPendingDownloads.remove(source)
            Log.w(TAG, "executor shut down, dropping image fetch", e)
        }
    }

    private fun notifyImageLoaded() {
        mMainHandler.removeCallbacks(mNotifyRunnable)
        mMainHandler.post(mNotifyRunnable)
    }

    private fun getBase64Image(base64: String): Bitmap? {
        val src = decodeBase64Bytes(base64) ?: return null
        return decodeBoundedImage(src, CHAT_MAX_DIMENSION, CHAT_MAX_PIXELS.toLong())
    }

    private fun fetchURLImage(source: String): Bitmap? {
        try {
            var url = URL(source)
            var redirectsFollowed = 0
            while (true) {
                val result = fetchOneUrl(url)
                if (!result.redirect) {
                    return result.bitmap
                }
                if (!SsrfHostPolicy.shouldFollowRedirect(
                        redirectsFollowed, result.status, result.location
                    )
                ) {
                    Log.w(TAG, "stopping redirect chain")
                    return null
                }
                redirectsFollowed++
                url = URL(url, result.location)
            }
        } catch (e: IOException) {
            Log.w(TAG, "failed to load URL image: $e")
        } catch (e: RuntimeException) {
            // Best-effort background fetch: an unexpected runtime failure skips the
            // image instead of killing the executor worker (the null return still
            // records the failure via mFailedDownloads in the caller).
            Log.w(TAG, "failed to load URL image: $e")
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OOM decoding URL image: $e")
        }
        return null
    }

    /** Outcome of one fetch hop: either a decoded bitmap or a redirect. */
    private class FetchResult {
        val bitmap: Bitmap?
        val redirect: Boolean
        val status: Int
        val location: String?

        constructor(bitmap: Bitmap?) {
            this.bitmap = bitmap
            this.redirect = false
            this.status = -1
            this.location = null
        }

        constructor(status: Int, location: String?) {
            this.bitmap = null
            this.redirect = true
            this.status = status
            this.location = location
        }
    }

    /**
     * Fetches a single URL without following redirects. Every hop
     * re-applies the scheme, userinfo, and SSRF host checks, so a
     * redirect to a private IP or a non-http(s) scheme is refused.
     */
    @Throws(IOException::class)
    private fun fetchOneUrl(url: URL): FetchResult {
        if (!isSchemeAllowed(url) || SsrfHostPolicy.hasUserinfo(url)) {
            Log.w(TAG, "Refusing to load image with disallowed URL")
            return FetchResult(null as Bitmap?)
        }
        val checked = resolveAndCheck(url) // null = blocked (fail closed), per-hop
        if (checked == null) {
            Log.w(TAG, "Refusing to load image from blocked host")
            return FetchResult(null as Bitmap?)
        }
        var lastFailure: IOException? = null
        for (addr in checked) {
            try {
                return fetchPinned(url, addr)
            } catch (e: IOException) {
                lastFailure = e // try the next checked address
            }
        }
        val failure = lastFailure
        if (failure != null) {
            throw failure // all checked addresses failed; loop treats as fetch failure
        }
        return FetchResult(null as Bitmap?) // unreachable: resolveAndCheck never returns empty
    }

    companion object {
        private const val TAG = "MumbleImageGetter"

        /** The maximum image size in bytes to load. */
        private const val MAX_LENGTH = 10 * 1024 * 1024

        /**
         * Receive-side decode caps for chat images. The send path already caps
         * outgoing images at 1600 px, so 4096 px / ~16 MP comfortably covers
         * interop without handing the decoder an unbounded allocation.
         */
        const val CHAT_MAX_DIMENSION = 4096
        const val CHAT_MAX_PIXELS = 16 * 1024 * 1024

        /**
         * Absolute rejection ceiling applied before the sampled decode. Anything
         * past this is a compressed bomb, never a photo: refuse outright instead
         * of downsampling.
         */
        const val ABSOLUTE_MAX_DIMENSION = 32768
        const val ABSOLUTE_MAX_PIXELS = 64L * 1024 * 1024

        /** Estimated total horizontal padding/margin around chat message text in dp. */
        private const val HORIZONTAL_PADDING_DP = 48

        /** Maximum dimension in dp for an image to be treated as an icon/emoji rather than a photo. */
        const val ICON_MAX_SIZE_DP = 96

        /**
         * Timeout for external image HTTP connection and stream reads in milliseconds.
         * Safe to keep at 15000ms as requests are performed asynchronously in background threads.
         */
        private const val NETWORK_TIMEOUT_MS = 15000

        /**
         * Calculates the display bounds for an image so that it fits the screen width (accounting for padding)
         * while preserving aspect ratio, and capping maximum height to prevent tall images from dominating the viewport.
         * Small icons/emojis are scaled according to display density rather than stretched to the full screen width.
         */
        @JvmStatic
        fun calculateImageBounds(
            intrinsicWidth: Int,
            intrinsicHeight: Int,
            displayWidth: Int,
            displayHeight: Int,
            horizontalPaddingPx: Int
        ): ImageBounds? {
            return calculateImageBounds(
                intrinsicWidth, intrinsicHeight, displayWidth, displayHeight,
                horizontalPaddingPx, 1.0f
            )
        }

        @JvmStatic
        fun calculateImageBounds(
            intrinsicWidth: Int,
            intrinsicHeight: Int,
            displayWidth: Int,
            displayHeight: Int,
            horizontalPaddingPx: Int,
            density: Float
        ): ImageBounds? {
            if (intrinsicWidth <= 0 || intrinsicHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
                return null
            }

            val safeDensity = if (!density.isNaN() && density.isFinite() && density > 0f) density else 1.0f
            val safePadding = maxOf(0, horizontalPaddingPx)
            val maxWidth = maxOf(displayWidth - safePadding, 1)
            val maxHeight = maxOf((displayHeight * 0.65f).toInt(), 1)

            val targetWidth: Int
            var targetHeight: Int

            val widthDp = intrinsicWidth / safeDensity
            val heightDp = intrinsicHeight / safeDensity

            if (widthDp <= ICON_MAX_SIZE_DP && heightDp <= ICON_MAX_SIZE_DP) {
                // Small icon / emoji: scale according to display density
                targetWidth = minOf(maxWidth, maxOf(1, (intrinsicWidth * safeDensity).roundToInt()))
                targetHeight = maxOf(1, (intrinsicHeight.toFloat() * targetWidth / intrinsicWidth.toFloat()).roundToInt())
            } else {
                // Photo / chat image: expand to fill chat width
                targetWidth = maxWidth
                targetHeight = maxOf(1, (intrinsicHeight.toFloat() * maxWidth / intrinsicWidth.toFloat()).roundToInt())
            }

            var finalWidth = targetWidth
            if (targetHeight > maxHeight) {
                targetHeight = maxHeight
                finalWidth = maxOf(1, (intrinsicWidth.toFloat() * maxHeight / intrinsicHeight.toFloat()).roundToInt())
            }

            return ImageBounds(finalWidth, targetHeight)
        }

        @JvmStatic
        fun percentDecode(s: String?): String? {
            if (s == null || s.indexOf('%') == -1) {
                return s
            }
            try {
                val decoded = Uri.decode(s)
                if (decoded != null) {
                    return decoded
                }
            } catch (ignored: Throwable) {
            }
            // Fallback for JVM unit tests or unmocked stubs
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val d1 = Character.digit(s[i + 1], 16)
                    val d2 = Character.digit(s[i + 2], 16)
                    if (d1 != -1 && d2 != -1) {
                        sb.append(((d1 shl 4) or d2).toChar())
                        i += 2
                        i++
                        continue
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        // No SDK_INT gate needed: minSdk 33 always has java.util.Base64
        // (the API-26 note below is history). The catch (Throwable) blocks
        // still deliberately cover the JVM unit-test path, where
        // android.util.Base64 is stubbed out on the desktop JVM.
        @SuppressLint("NewApi")
        @JvmStatic
        fun decodeBase64Bytes(base64: String?): ByteArray? {
            if (base64 == null || base64.isEmpty()) {
                return null
            }
            val decodedBase64 = percentDecode(base64)
            var src: ByteArray?
            try {
                src = Base64.decode(decodedBase64, Base64.DEFAULT)
                if (src == null) {
                    try {
                        src = java.util.Base64.getMimeDecoder().decode(decodedBase64)
                    } catch (ignored: Throwable) {
                        return null
                    }
                }
            } catch (t: Throwable) {
                try {
                    src = java.util.Base64.getMimeDecoder().decode(decodedBase64)
                } catch (ignored: Throwable) {
                    return null
                }
            }
            if (src == null || src.isEmpty() || src.size > MAX_LENGTH) {
                return null
            }
            return src
        }

        /**
         * Pure power-of-two sampler: smallest sample size keeping both sides
         * within maxDim and total pixels within maxPixels. Invalid inputs fail
         * closed (sample size 1 lets the absolute-rejection gate decide).
         */
        @JvmStatic
        fun calculateInSampleSize(width: Int, height: Int, maxDim: Int, maxPixels: Long): Int {
            if (width <= 0 || height <= 0 || maxDim <= 0 || maxPixels <= 0) {
                return 1
            }
            var sampleSize = 1
            while (width / sampleSize > maxDim ||
                height / sampleSize > maxDim ||
                (width / sampleSize).toLong() * (height / sampleSize) > maxPixels
            ) {
                sampleSize *= 2
            }
            return sampleSize
        }

        /** True when raw dimensions exceed the absolute rejection ceiling. */
        @JvmStatic
        fun isOversize(width: Int, height: Int): Boolean {
            if (width <= 0 || height <= 0) {
                return true
            }
            return width > ABSOLUTE_MAX_DIMENSION ||
                height > ABSOLUTE_MAX_DIMENSION ||
                width.toLong() * height > ABSOLUTE_MAX_PIXELS
        }

        /**
         * Two-pass bounded decode: reads dimensions first via
         * inJustDecodeBounds, rejects bombs past the absolute ceiling, then
         * downsamples to the chat caps (4096 px / ~16 MP) before the full
         * decode. Synchronous by design: getDrawable runs base64 decode on the
         * UI thread and fetchURLImage already runs on mExecutor, so no
         * signature change or pop-in is needed. Returns null on rejection.
         */
        @JvmStatic
        fun decodeBoundedImage(data: ByteArray?, maxDim: Int, maxPixels: Long): Bitmap? {
            if (data == null || data.isEmpty() || data.size > MAX_LENGTH) {
                return null
            }
            try {
                val bounds = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                val width = bounds.outWidth
                val height = bounds.outHeight
                if (width <= 0 || height <= 0 || isOversize(width, height)) {
                    Log.w(TAG, "rejecting oversize image: " + width + "x" + height)
                    return null
                }
                val decode = BitmapFactory.Options()
                decode.inSampleSize = calculateInSampleSize(width, height, maxDim, maxPixels)
                return BitmapFactory.decodeByteArray(data, 0, data.size, decode)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "OOM decoding bounded image: $e")
                return null
            }
        }

        // Widened to public: Kotlin has no package visibility (pre-image was package-visible).
        @JvmStatic
        fun isSchemeAllowed(url: URL?): Boolean {
            if (url == null) {
                return false
            }
            val protocol = url.protocol
            return protocol != null &&
                (protocol.equals("http", ignoreCase = true) || protocol.equals("https", ignoreCase = true))
        }

        /**
         * Resolve-once SSRF check: literals are classified without DNS, hostnames are
         * resolved and refused when any address is blocked. Returns the checked
         * addresses, or null when the host is refused (fail closed). Every redirect
         * hop re-resolves and re-pins through fetchOneUrl.
         */
        // Companion (was static in the pre-image): fetches a single URL from one
        // checked address without following redirects.
        private fun fetchPinned(url: URL, addr: InetAddress): FetchResult {
            val conn = SsrfHostPolicy.buildPinnedUrl(url, addr).openConnection()
            if (conn !is HttpURLConnection) {
                return FetchResult(null as Bitmap?)
            }
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = NETWORK_TIMEOUT_MS
                conn.readTimeout = NETWORK_TIMEOUT_MS
                conn.setRequestProperty("Host", SsrfHostPolicy.hostHeaderValue(url))
                if (conn is HttpsURLConnection) {
                    val sniHost = SsrfHostPolicy.normalizeHost(url.host)
                    conn.sslSocketFactory = PinnedTlsSocketFactory(
                        sniHost ?: throw NullPointerException("host")
                    )
                    conn.hostnameVerifier = PinnedTlsSocketFactory.verifierFor(
                        sniHost ?: throw NullPointerException("host")
                    )
                }
                val status = conn.responseCode
                if (SsrfHostPolicy.isRedirect(status)) {
                    return FetchResult(status, conn.getHeaderField("Location"))
                }

                val contentLength = conn.contentLength
                if (contentLength > MAX_LENGTH) {
                    return FetchResult(null as Bitmap?)
                }

                conn.inputStream.use { input ->
                    val data = readStreamWithLimit(input, MAX_LENGTH)
                    if (data == null || data.isEmpty()) {
                        return FetchResult(null as Bitmap?)
                    }
                    return FetchResult(
                        decodeBoundedImage(data, CHAT_MAX_DIMENSION, CHAT_MAX_PIXELS.toLong())
                    )
                }
            } finally {
                conn.disconnect()
            }
        }

        private fun resolveAndCheck(url: URL): Array<InetAddress>? {
            val host = url.host
            if (host == null || host.isEmpty()) {
                return null
            }
            if (SsrfHostPolicy.looksLikeIpLiteral(host)) {
                if (SsrfHostPolicy.isLiteralBlocked(host)) {
                    return null
                }
                try {
                    return arrayOf(InetAddress.getByName(SsrfHostPolicy.normalizeHost(host)))
                } catch (e: UnknownHostException) {
                    return null
                }
            }
            try {
                val addresses = InetAddress.getAllByName(SsrfHostPolicy.normalizeHost(host))
                if (SsrfHostPolicy.isAnyAddressBlocked(addresses)) {
                    return null
                }
                return addresses
            } catch (e: UnknownHostException) {
                return null
            }
        }

        @Throws(IOException::class)
        private fun readStreamWithLimit(`is`: InputStream, maxBytes: Int): ByteArray? {
            val baos = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var totalRead = 0
            while (true) {
                val bytesRead = `is`.read(buffer)
                if (bytesRead == -1) break
                totalRead += bytesRead
                if (totalRead > maxBytes) {
                    return null // Exceeded maximum allowable byte size
                }
                baos.write(buffer, 0, bytesRead)
            }
            return baos.toByteArray()
        }
    }
}

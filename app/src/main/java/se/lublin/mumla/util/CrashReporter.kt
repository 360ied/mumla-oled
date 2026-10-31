/*
 * Copyright (C) 2026 Brian Zhu
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

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import se.lublin.mumla.BuildConfig

/**
 * Installable crash recorder: uncaught-handler fidelity plus
 * ApplicationExitInfo backstop drain with causal-key dedup.
 *
 * Crash-path uses only synchronous, allocation-light file I/O (no DataStore,
 * Hilt, coroutines, or runBlocking). Handler work is capped; delegation to
 * the previous handler happens in a finally block.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val DIR_NAME = "crash-reports"
    private const val WATERMARK_NAME = "watermark"
    private const val DEDUP_WINDOW_MS = 60_000L
    private const val TRACE_CAP_BYTES = 256 * 1024

    @Volatile
    private var handlerInstalled = false
    private val installLock = Any()

    fun storeDir(context: Context): File {
        // Throws on locked storage — callers must catch (all current callers do).
        return File(context.noBackupFilesDir, DIR_NAME)
    }

    fun store(context: Context): CrashReportStore {
        return CrashReportStore(storeDir(context))
    }

    /**
     * First call after `super.onCreate()`, before any other app work.
     * Safe to call twice: the handler installs once, the exit-info drain
     * is watermark-guarded and idempotent so re-drains are safe.
     */
    @JvmStatic
    fun install(context: Context) {
        val already: Boolean
        synchronized(installLock) {
            already = handlerInstalled
            if (!already) handlerInstalled = true
        }
        if (already) {
            // Re-entry (safe to call twice): drain outside the lock so a
            // concurrent first install doing Binder/file I/O is not blocked.
            drainAsync(context.applicationContext)
            return
        }
        try {
            installHandler(context.applicationContext)
        } catch (e: Throwable) {
            Log.w(TAG, "Handler install failed", e)
            synchronized(installLock) {
                handlerInstalled = false
            }
        }
        // Exit-info drain does IPC + file I/O; keep it off the main thread.
        // Handler fidelity needs no drain — the activity re-drain covers the
        // same-launch prompt if this is still in flight.
        drainAsync(context.applicationContext)
    }

    private fun drainAsync(appContext: Context) {
        try {
            Thread({
                try {
                    drainExitInfo(appContext)
                } catch (_: Throwable) {
                }
            }, "crash-drain").start()
        } catch (_: Throwable) {
            // Fall back to sync so a prompt is never lost for lack of a thread.
            try {
                drainExitInfo(appContext)
            } catch (_: Throwable) {
            }
        }
    }

    private fun installHandler(appContext: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeHandlerReport(appContext, thread, throwable)
            } catch (_: Throwable) {
            } finally {
                try {
                    if (previous != null) {
                        previous.uncaughtException(thread, throwable)
                    } else {
                        // No system handler (theoretical): kill explicitly so
                        // a fatal throw never leaves a corrupted process alive.
                        try {
                            Process.killProcess(Process.myPid())
                        } catch (_: Throwable) {
                        }
                        try {
                            System.exit(NO_HANDLER_EXIT_CODE)
                        } catch (_: Throwable) {
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** Writer that drops beyond maxChars so huge stacks never materialize.
     * Overrides every StringWriter mutator: PrintWriter.print/println
     * funnels through write(String,off,len), and append() touches the
     * buffer directly — missing either reopens the OOM path. */
    private class BoundedStringWriter(private val maxChars: Int) : StringWriter(8192) {
        override fun write(cbuf: CharArray, off: Int, len: Int) {
            val remaining = maxChars - buffer.length
            if (remaining <= 0) return
            super.write(cbuf, off, minOf(len, remaining))
        }
        override fun write(str: String) {
            write(str, 0, str.length)
        }
        override fun write(str: String, off: Int, len: Int) {
            val remaining = maxChars - buffer.length
            if (remaining <= 0) return
            super.write(str, off, minOf(len, remaining))
        }
        override fun write(c: Int) {
            if (buffer.length >= maxChars) return
            super.write(c)
        }
        override fun append(csq: CharSequence?): StringWriter {
            if (csq == null) return this
            write(csq.toString())
            return this
        }
        override fun append(csq: CharSequence?, start: Int, end: Int): StringWriter {
            if (csq == null) return this
            write(csq.toString().substring(start, end))
            return this
        }
        override fun append(c: Char): StringWriter {
            write(c.code)
            return this
        }
    }

    // Theoretical no-previous-handler branch only; named for greppability.
    private const val NO_HANDLER_EXIT_CODE = 10

    private fun writeHandlerReport(appContext: Context, thread: Thread, throwable: Throwable) {
        try {
            val capturedAt = System.currentTimeMillis()
            val pid = Process.myPid()
            // Capped during write (not just after): huge stacks (e.g.
            // StackOverflowError with 10k frames) never fully materialize.
            // TRACE_CAP_BYTES is bytes; traces are ASCII-dominant so chars
            // approximate bytes here — the post-write byte cap stays exact.
            val sw = BoundedStringWriter(TRACE_CAP_BYTES)
            val pw = PrintWriter(sw)
            try {
                pw.println("process=" + safeProcessName())
                pw.println("thread=" + thread.name)
                throwable.printStackTrace(pw)
            } catch (_: Throwable) {
            } finally {
                try {
                    pw.flush()
                } catch (_: Throwable) {
                }
            }
            // Byte-cap (not char-cap): TRACE_CAP_BYTES is bytes everywhere
            // (readTraceCapped, CrashReportStore.MAX_FILE_BYTES).
            var raw = sw.toString()
            var handlerTruncated = false
            val rawBytes = raw.toByteArray(Charsets.UTF_8)
            if (rawBytes.size > TRACE_CAP_BYTES) {
                // String(byte[], off, len, charset) replaces a split trailing
                // multi-byte sequence instead of emitting invalid UTF-8.
                raw = String(rawBytes, 0, TRACE_CAP_BYTES, Charsets.UTF_8)
                handlerTruncated = true
            }
            val scrubbed = try {
                CrashReportScrubber.scrub(raw)
            } catch (_: Throwable) {
                ""
            }
            // Thread id disambiguates two threads crashing in the same
            // millisecond on the same pid (provisional id only; the
            // surviving exit file keeps the exit-based id).
            val id = "${capturedAt}-${pid}-${thread.id}-pending"
            val json = CrashReportStore.toJsonCapped(
                id,
                null,
                capturedAt,
                capturedAt,
                "uncaught-handler",
                "REASON_CRASH",
                safeVersionName(),
                Build.VERSION.RELEASE,
                Build.MODEL,
                safeAbi(),
                safeProcessName(),
                pid,
                handlerTruncated,
                scrubbed,
                0L,
                0L,
            )
            try {
                CrashReportStore(storeDir(appContext)).write(id, json)
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
    }

    fun drainExitInfo(appContext: Context) {
        try {
            val am = appContext.getSystemService(ActivityManager::class.java) ?: return
            val reasons = try {
                am.getHistoricalProcessExitReasons(appContext.packageName, 0, 0)
            } catch (_: Throwable) {
                return
            } ?: return
            val dir = storeDir(appContext)
            val store = CrashReportStore(dir)
            if (reasons.isEmpty()) {
                // No history (e.g. after reboot or system eviction): still
                // retry a pending merge so a prior transient failure is not
                // stuck until the next non-empty drain.
                try {
                    mergePending(store)
                } catch (_: Throwable) {
                }
                return
            }
            var watermark = readWatermark(dir)
            var maxSeen = watermark
            for (info in reasons) {
                try {
                    val ts = info.timestamp
                    // '<' (not '<='): same-millisecond exits are rechecked;
                    // store.exists() dedups already-recorded ids.
                    // Accepted limitation (design §2/§6): timestamps are wall
                    // clock, so a backward clock jump can skip a new crash
                    // (no monotonic clock in ApplicationExitInfo).
                    if (ts < watermark) {
                        continue
                    }
                    val reason = info.reason
                    if (reason != ApplicationExitInfo.REASON_CRASH &&
                        reason != ApplicationExitInfo.REASON_CRASH_NATIVE &&
                        reason != ApplicationExitInfo.REASON_ANR
                    ) {
                        // Filtered (low-memory, normal exits): advance past.
                        if (ts > maxSeen) maxSeen = ts
                        continue
                    }
                    // Crash/ANR: advance only on successful write so a
                    // transient failure (disk-full, locked storage) is
                    // retried next launch instead of skipped.
                    if (writeExitReport(dir, store, info)) {
                        if (ts > maxSeen) maxSeen = ts
                    }
                } catch (_: Throwable) {
                }
            }
            // Merge handler-written pending files with fresh exit entries.
            try {
                mergePending(store)
            } catch (_: Throwable) {
            }
            if (maxSeen > watermark) {
                writeWatermark(dir, maxSeen)
            }
        } catch (_: Throwable) {
        }
    }

    private fun writeExitReport(
        dir: File,
        store: CrashReportStore,
        info: ApplicationExitInfo,
    ): Boolean {
        val pid = info.pid
        val ts = info.timestamp
        val reasonStr = reasonName(info.reason)
        val id = "${ts}-${pid}"
        // Skip if already recorded (relaunch without watermark advance).
        try {
            if (store.exists(id)) {
                return true
            }
        } catch (_: Throwable) {
        }
        val (trace, truncated) = readTraceCapped(info)
        val scrubbed = try {
            CrashReportScrubber.scrub(trace)
        } catch (_: Throwable) {
            ""
        }
        val rss = try {
            info.rss
        } catch (_: Throwable) {
            0L
        }
        val pss = try {
            info.pss
        } catch (_: Throwable) {
            0L
        }
        val processName: String = try {
            info.processName ?: safeProcessName()
        } catch (_: Throwable) {
            safeProcessName()
        }
        val json = CrashReportStore.toJsonCapped(
            id,
            ts,
            System.currentTimeMillis(),
            null,
            "exit-info",
            reasonStr,
            safeVersionName(),
            Build.VERSION.RELEASE,
            Build.MODEL,
            safeAbi(),
            processName,
            pid,
            truncated,
            scrubbed,
            rss,
            pss,
        )
        return try {
            store.write(id, json) != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun readTraceCapped(info: ApplicationExitInfo): Pair<String, Boolean> {
        var stream: java.io.InputStream? = null
        try {
            stream = try {
                info.traceInputStream
            } catch (_: Throwable) {
                return Pair("", false)
            } ?: return Pair("", false)
            val out = ByteArrayOutputStream(8192)
            val buf = ByteArray(8192)
            var total = 0
            var truncated = false
            var zeroStreak = 0
            while (true) {
                val n = try {
                    stream.read(buf)
                } catch (_: Throwable) {
                    break
                }
                // EOF is -1; 0 is a legal short read, not end-of-stream.
                if (n < 0) {
                    break
                }
                if (n == 0) {
                    // Defensive: a faulty stream returning 0 forever would
                    // stall process death; cap consecutive short reads.
                    if (++zeroStreak > 32) break
                    continue
                }
                zeroStreak = 0
                if (total + n > TRACE_CAP_BYTES) {
                    out.write(buf, 0, TRACE_CAP_BYTES - total)
                    truncated = true
                    break
                }
                out.write(buf, 0, n)
                total += n
            }
            val text = try {
                String(out.toByteArray(), Charsets.UTF_8)
            } catch (_: Throwable) {
                ""
            }
            return Pair(text, truncated)
        } catch (_: Throwable) {
            return Pair("", false)
        } finally {
            try {
                stream?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Causal-key dedup: a pending handler file joins an exit-info file with
     * the same pid + reason whose timestamp falls within 60s after the
     * handler write. The handler file records its own write time and pid, and
     * matches an exit entry with the same pid/reason. Clock skew fails safe
     * toward duplicates: a jumped clock can only miss a merge, never lose one.
     */
    // Secondary confirmation (not primary key): when both sides carry a
    // non-empty scrubbed trace, the first 512 chars must agree before a
    // pending provisional is dropped. Trace-less entries still merge on the
    // causal pid+reason+window key alone.
    private fun tracesConfirm(pendingRaw: String, exitRaw: String): Boolean {
        return try {
            var a = extractTrace(pendingRaw)
            val b = extractTrace(exitRaw)
            if (a.isEmpty() || b.isEmpty()) {
                return true
            }
            // Pending traces carry 'process='/'thread=' headers the exit
            // stream lacks; strip them so the 512-char window aligns.
            a = a.lineSequence()
                .filterNot { it.startsWith("process=") || it.startsWith("thread=") }
                .joinToString("\n")
            if (a.isEmpty()) return true
            a.take(512) == b.take(512)
        } catch (_: Throwable) {
            // Fail safe toward duplicates: an unreadable trace never
            // authorizes dropping the pending file.
            false
        }
    }

    fun mergePending(store: CrashReportStore) {
        try {
            val entries = store.list()
            val pending = entries.filter { it.id.endsWith("-pending") }
            if (pending.isEmpty()) {
                return
            }
            val exits = entries.filter { !it.id.endsWith("-pending") }
            for (p in pending) {
                try {
                    val raw = store.readRaw(p.file) ?: continue
                    val handlerTime = extractLong(raw, "handlerWriteTime")
                        ?: extractLong(raw, "capturedAtUtc") ?: continue
                    val pid = extractLong(raw, "pid") ?: continue
                    val reason = extractString(raw, "reason") ?: continue
                    var match: CrashReportStore.Entry? = null
                    for (e in exits) {
                        if (e.reason != reason) {
                            continue
                        }
                        val exitTs = e.exitTimestamp ?: continue
                        val exitPid = extractPidFromId(e.id) ?: continue
                        if (exitPid != pid) {
                            continue
                        }
                        if (exitTs >= handlerTime && exitTs - handlerTime <= DEDUP_WINDOW_MS) {
                            match = e
                            break
                        }
                    }
                    if (match != null) {
                        // Exit file keeps the exit-based id (provisional id is
                        // dropped); its handlerWriteTime stays null by schema.
                        // Confirm content before dropping the pending file.
                        // Fail safe toward duplicates: unreadable exit content
                        // keeps the pending file, never deletes it.
                        val drop = try {
                            val exitRaw = store.readRaw(match.file)
                            exitRaw != null && tracesConfirm(raw, exitRaw)
                        } catch (_: Throwable) {
                            false
                        }
                        if (drop) {
                            try {
                                store.delete(p)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun extractPidFromId(id: String): Long? {
        // Exit id: "<exitTs>-<pid>"; pending id: "<ts>-<pid>-<tid>-pending"
        // (legacy "<ts>-<pid>-pending" still parses). Keyed on the -pending
        // suffix; pid is second-last when a thread id is present.
        return try {
            if (id.endsWith("-pending")) {
                val core = id.removeSuffix("-pending")
                val parts = core.split("-")
                when {
                    parts.size >= 3 -> parts[parts.size - 2].toLongOrNull()
                    parts.size == 2 -> parts[1].toLongOrNull()
                    else -> core.substringAfterLast("-").toLongOrNull()
                }
            } else {
                id.substringAfterLast("-").toLongOrNull()
            }
        } catch (_: Throwable) {
            null
        }
    }

    // Hand-rolled extractors (no JSON lib on the crash path). Invariant:
    // toJson emits scalar fields (id, pid, timestamps) before scrubbedTrace,
    // so the first occurrence of a field key is the header value even when
    // the trace body contains a quoted literal like "pid":.
    // Header-bounded (mirrors CrashReportStore.parseSummary): every scalar
    // precedes scrubbedTrace (trace always last), so a trace-body literal
    // can never shadow a header value.
    private fun headerSlice(json: String): String {
        return try {
            val i = json.indexOf("\"scrubbedTrace\"")
            if (i < 0) json else json.substring(0, i)
        } catch (_: Throwable) {
            json
        }
    }

    private fun extractLong(json: String, name: String): Long? {
        return try {
            val haystack = headerSlice(json)
            val key = "\"" + name + "\":"
            val i = haystack.indexOf(key)
            if (i < 0) {
                return null
            }
            val rest = haystack.substring(i + key.length).trimStart()
            if (rest.startsWith("null")) {
                return null
            }
            var end = 0
            if (rest.startsWith("-")) end = 1
            while (end < rest.length && rest[end].isDigit()) end++
            if (end == 0 || (end == 1 && rest.startsWith("-"))) return null
            rest.substring(0, end).toLongOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractString(json: String, name: String): String? {
        // Escape-aware + header-bounded (mirrors CrashReportStore.parseSummary).
        return try {
            val haystack = headerSlice(json)
            val key = "\"" + name + "\":\""
            val i = haystack.indexOf(key)
            if (i < 0) {
                return null
            }
            var j = i + key.length
            val sb = StringBuilder()
            var closed = false
            while (j < haystack.length) {
                val c = haystack[j]
                if (c == '\\' && j + 1 < haystack.length) {
                    val n = haystack[j + 1]
                    when (n) {
                        '"', '\\', '/' -> { sb.append(n); j += 2 }
                        'n' -> { sb.append('\n'); j += 2 }
                        'r' -> { sb.append('\r'); j += 2 }
                        't' -> { sb.append('\t'); j += 2 }
                        'b' -> { sb.append('\b'); j += 2 }
                        'f' -> { sb.append('\u000C'); j += 2 }
                        'u' -> {
                            if (j + 5 < haystack.length) {
                                sb.append(haystack.substring(j + 2, j + 6).toInt(16).toChar())
                                j += 6
                            } else return null
                        }
                        else -> { sb.append(c); j++ }
                    }
                } else if (c == '"') {
                    closed = true
                    break
                } else {
                    sb.append(c)
                    j++
                }
            }
            if (!closed) return null
            sb.toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun readWatermark(dir: File): Long {
        return try {
            val f = File(dir, WATERMARK_NAME)
            if (!f.isFile) {
                return 0L
            }
            f.readText(Charsets.UTF_8).trim().toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun writeWatermark(dir: File, value: Long) {
        try {
            if (!dir.isDirectory) {
                dir.mkdirs()
            }
            val tmp = File(dir, "$WATERMARK_NAME.tmp")
            tmp.writeText(value.toString(), Charsets.UTF_8)
            val dest = File(dir, WATERMARK_NAME)
            if (!tmp.renameTo(dest)) {
                dest.writeText(value.toString(), Charsets.UTF_8)
                try {
                    tmp.delete()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun reasonName(reason: Int): String {
        return when (reason) {
            ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
            else -> "REASON_$reason"
        }
    }

    private fun safeVersionName(): String {
        return try {
            BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
        } catch (_: Throwable) {
            "unknown"
        }
    }

    private fun safeAbi(): String {
        return try {
            Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        } catch (_: Throwable) {
            "unknown"
        }
    }

    private fun safeProcessName(): String {
        return try {
            Application.getProcessName()
        } catch (_: Throwable) {
            "se.lublin.mumla.oled15"
        }
    }

    /**
     * Renders the share payload from stored raw JSON: a one-line summary plus
     * the scrubbed .txt body (preview = payload, never the raw JSON).
     * Includes triage headers (os/device/abi/process/truncated/rss) so the
     * recipient can tell a capped trace from a complete one.
     */
    @JvmStatic
    fun renderSharePayload(rawJson: String): Pair<String, String>? {
        return try {
            val id = extractString(rawJson, "id") ?: "report"
            val reason = extractString(rawJson, "reason") ?: "unknown"
            val appVersion = extractString(rawJson, "appVersion") ?: "unknown"
            val osVersion = extractString(rawJson, "osVersion") ?: "unknown"
            val deviceModel = extractString(rawJson, "deviceModel") ?: "unknown"
            val abi = extractString(rawJson, "abi") ?: "unknown"
            val processName = extractString(rawJson, "processName") ?: "unknown"
            val source = extractString(rawJson, "source") ?: "unknown"
            val pid = extractLong(rawJson, "pid")
            val rss = extractLong(rawJson, "rssKb")
            val pss = extractLong(rawJson, "pssKb")
            val trace = extractTrace(rawJson)
            val summary = "Mumla OLED crash report $id ($reason)"
            val sb = StringBuilder()
            sb.append("Mumla OLED crash report\n")
            sb.append("id: ").append(id).append("\n")
            sb.append("reason: ").append(reason).append("\n")
            sb.append("appVersion: ").append(appVersion).append("\n")
            sb.append("osVersion: ").append(osVersion).append("\n")
            sb.append("deviceModel: ").append(deviceModel).append("\n")
            sb.append("abi: ").append(abi).append("\n")
            sb.append("processName: ").append(processName).append("\n")
            sb.append("source: ").append(source).append("\n")
            if (pid != null) sb.append("pid: ").append(pid).append("\n")
            if (rss != null) sb.append("rssKb: ").append(rss).append("\n")
            if (pss != null) sb.append("pssKb: ").append(pss).append("\n")
            val captured = extractLong(rawJson, "capturedAtUtc")
            if (captured != null) sb.append("capturedAtUtc: ").append(captured).append("\n")
            val handlerWrite = extractLong(rawJson, "handlerWriteTime")
            if (handlerWrite != null) sb.append("handlerWriteTime: ").append(handlerWrite).append("\n")
            // Explicit true/false (not just when true) so the recipient can
            // tell a capped trace from a complete one. Whitespace-tolerant:
            // future pretty-printing must not silently flip shares to false.
            val headerEnd = rawJson.indexOf("\"scrubbedTrace\"")
            val header = if (headerEnd >= 0) rawJson.substring(0, headerEnd) else rawJson
            val truncatedFlag = try {
                Regex("\"truncated\"\\s*:\\s*true").containsMatchIn(header)
            } catch (_: Throwable) {
                header.contains("\"truncated\":true")
            }
            sb.append("truncated: ").append(if (truncatedFlag) "true" else "false").append("\n")
            val exitTs = extractLong(rawJson, "exitTimestamp")
            if (exitTs != null) {
                sb.append("exitTimestamp: ").append(exitTs).append("\n")
            }
            sb.append("\n")
            sb.append(trace)
            Pair(summary, sb.toString())
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractTrace(rawJson: String): String {
        // Scrubbed trace is JSON-escaped; unescape minimally.
        return try {
            val key = "\"scrubbedTrace\":\""
            val i = rawJson.indexOf(key)
            if (i < 0) {
                return ""
            }
            var j = i + key.length
            val sb = StringBuilder()
            while (j < rawJson.length) {
                val c = rawJson[j]
                if (c == '\\' && j + 1 < rawJson.length) {
                    when (rawJson[j + 1]) {
                        'n' -> {
                            sb.append('\n')
                            j += 2
                        }
                        'r' -> {
                            sb.append('\r')
                            j += 2
                        }
                        't' -> {
                            sb.append('\t')
                            j += 2
                        }
                        'b' -> {
                            sb.append('\b')
                            j += 2
                        }
                        'f' -> {
                            sb.append('\u000C')
                            j += 2
                        }
                        '"', '\\', '/' -> {
                            sb.append(rawJson[j + 1])
                            j += 2
                        }
                        'u' -> {
                            if (j + 5 < rawJson.length) {
                                sb.append(rawJson.substring(j + 2, j + 6).toInt(16).toChar())
                                j += 6
                            } else {
                                break
                            }
                        }
                        else -> {
                            sb.append(c)
                            j++
                        }
                    }
                } else if (c == '"') {
                    break
                } else {
                    sb.append(c)
                    j++
                }
            }
            sb.toString()
        } catch (_: Throwable) {
            ""
        }
    }
}

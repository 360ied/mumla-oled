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
    private var installed = false
    private val installLock = Any()

    fun storeDir(context: Context): File {
        return File(context.noBackupFilesDir, DIR_NAME)
    }

    fun store(context: Context): CrashReportStore {
        return CrashReportStore(storeDir(context))
    }

    /**
     * Must run as the first statement of Application.onCreate().
     * Safe to call twice; second call is a no-op.
     */
    @JvmStatic
    fun install(context: Context) {
        synchronized(installLock) {
            if (installed) {
                return
            }
            installed = true
        }
        try {
            installHandler(context.applicationContext)
        } catch (e: Throwable) {
            Log.w(TAG, "Handler install failed", e)
        }
        try {
            drainExitInfo(context.applicationContext)
        } catch (e: Throwable) {
            Log.w(TAG, "Exit-info drain failed", e)
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
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun writeHandlerReport(appContext: Context, thread: Thread, throwable: Throwable) {
        try {
            val capturedAt = System.currentTimeMillis()
            val pid = Process.myPid()
            // Capped allocation: pre-sized buffer, truncated stack.
            val sw = StringWriter(8192)
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
            val id = "${capturedAt}-${pid}-pending"
            val json = CrashReportStore.toJson(
                id,
                null,
                capturedAt,
                capturedAt,
                "uncaught-handler",
                "REASON_CRASH",
                safeVersionName(),
                Build.VERSION.RELEASE ?: "unknown",
                Build.MODEL ?: "unknown",
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
            if (reasons.isEmpty()) {
                return
            }
            val dir = storeDir(appContext)
            val store = CrashReportStore(dir)
            var watermark = readWatermark(dir)
            var maxSeen = watermark
            for (info in reasons) {
                try {
                    val ts = info.timestamp
                    if (ts > maxSeen) {
                        maxSeen = ts
                    }
                    if (ts <= watermark) {
                        continue
                    }
                    val reason = info.reason
                    if (reason != ApplicationExitInfo.REASON_CRASH &&
                        reason != ApplicationExitInfo.REASON_CRASH_NATIVE &&
                        reason != ApplicationExitInfo.REASON_ANR
                    ) {
                        continue
                    }
                    writeExitReport(appContext, store, info)
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
        appContext: Context,
        store: CrashReportStore,
        info: ApplicationExitInfo,
    ) {
        val pid = info.pid
        val ts = info.timestamp
        val reasonStr = reasonName(info.reason)
        val id = "${ts}-${pid}"
        // Skip if already recorded (relaunch without watermark advance).
        try {
            val existing = File(storeDir(appContext), "$id.json")
            if (existing.isFile) {
                return
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
        val processName = try {
            info.processName ?: safeProcessName()
        } catch (_: Throwable) {
            safeProcessName()
        }
        val json = CrashReportStore.toJson(
            id,
            ts,
            System.currentTimeMillis(),
            null,
            "exit-info",
            reasonStr,
            safeVersionName(),
            Build.VERSION.RELEASE ?: "unknown",
            Build.MODEL ?: "unknown",
            safeAbi(),
            processName ?: safeProcessName(),
            pid,
            truncated,
            scrubbed,
            rss,
            pss,
        )
        try {
            store.write(id, json)
        } catch (_: Throwable) {
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
                    continue
                }
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
            val a = extractTrace(pendingRaw)
            val b = extractTrace(exitRaw)
            if (a.isEmpty() || b.isEmpty()) {
                return true
            }
            a.take(512) == b.take(512)
        } catch (_: Throwable) {
            true
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
                        val drop = try {
                            val exitRaw = store.readRaw(match.file)
                            exitRaw == null || tracesConfirm(raw, exitRaw)
                        } catch (_: Throwable) {
                            true
                        }
                        if (drop) {
                            try {
                                p.file.delete()
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
        // Exit id: "<exitTs>-<pid>"; pending id: "<ts>-<pid>-pending".
        // Keyed on the -pending suffix explicitly so a future dash in the
        // timestamp section cannot silently shift the index.
        return try {
            if (id.endsWith("-pending")) {
                id.removeSuffix("-pending").substringAfterLast("-").toLongOrNull()
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
    private fun extractLong(json: String, name: String): Long? {
        return try {
            val key = "\"" + name + "\":"
            val i = json.indexOf(key)
            if (i < 0) {
                return null
            }
            val rest = json.substring(i + key.length).trimStart()
            if (rest.startsWith("null")) {
                return null
            }
            val end = rest.indexOfFirst { !it.isDigit() && it != '-' }
            val num = if (end < 0) rest else rest.substring(0, end)
            num.toLongOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractString(json: String, name: String): String? {
        return try {
            val key = "\"" + name + "\":\""
            val i = json.indexOf(key)
            if (i < 0) {
                return null
            }
            val start = i + key.length
            val end = json.indexOf("\"", start)
            if (end < 0) {
                return null
            }
            json.substring(start, end)
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
     */
    @JvmStatic
    fun renderSharePayload(rawJson: String): Pair<String, String>? {
        return try {
            val id = extractString(rawJson, "id") ?: "report"
            val reason = extractString(rawJson, "reason") ?: "unknown"
            val appVersion = extractString(rawJson, "appVersion") ?: "unknown"
            val trace = extractTrace(rawJson)
            val summary = "Mumla OLED crash report $id ($reason)"
            val sb = StringBuilder()
            sb.append("Mumla OLED crash report\n")
            sb.append("id: ").append(id).append("\n")
            sb.append("reason: ").append(reason).append("\n")
            sb.append("appVersion: ").append(appVersion).append("\n")
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

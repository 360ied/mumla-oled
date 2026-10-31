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

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Pure file store rooted at a crash-reports directory (production:
 * `getNoBackupFilesDir()/crash-reports`).
 *
 * One JSON file per incident (UTF-8). Rotation keeps the newest 8.
 * All I/O is guarded and fail-silent; corrupt files are skipped, never
 * parsed leniently. Writes are atomic (tmp + rename) and truncated to
 * [MAX_FILE_BYTES].
 */
class CrashReportStore(private val dir: File) {
    data class Entry(
        val id: String,
        val file: File,
        val exitTimestamp: Long?,
        val source: String,
        val reason: String,
        val truncated: Boolean,
    )

    companion object {
        const val MAX_FILES = 8
        const val MAX_FILE_BYTES = 256 * 1024
        // Slack for a concurrently-grown file between length() and readText().
        // Private app dir; a planted oversized file still fully materializes
        // (accepted TOCTOU — bounds are a backstop, not a security gate).
        const val SIZE_SLOP_BYTES = 4096L
        private val SAFE_ID_REGEX = Regex("[^A-Za-z0-9._-]+")
        private val HEX = "0123456789abcdef".toCharArray()

        /** Shared filename sanitizer (store + share staging stay in sync). */
        @JvmStatic
        fun sanitizeId(id: String, maxLen: Int): String {
            return id.replace(SAFE_ID_REGEX, "_").take(maxLen).ifEmpty { "report" }
        }

        @JvmStatic
        fun jsonEscape(s: String): String {
            val sb = StringBuilder(s.length + 16)
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ' || c in '\uD800'..'\uDFFF') {
                        sb.append("\\u")
                        val v = c.code
                        sb.append(HEX[(v ushr 12) and 0xF])
                        sb.append(HEX[(v ushr 8) and 0xF])
                        sb.append(HEX[(v ushr 4) and 0xF])
                        sb.append(HEX[v and 0xF])
                    } else {
                        sb.append(c)
                    }
                }
            }
            return sb.toString()
        }

        @JvmStatic
        fun toJson(
            id: String,
            exitTimestamp: Long?,
            capturedAtUtc: Long,
            handlerWriteTime: Long?,
            source: String,
            reason: String,
            appVersion: String,
            osVersion: String,
            deviceModel: String,
            abi: String,
            processName: String,
            pid: Int,
            truncated: Boolean,
            scrubbedTrace: String,
            rssKb: Long,
            pssKb: Long,
        ): String {
            fun q(s: String) = "\"" + jsonEscape(s) + "\""
            fun num(n: Long?) = n?.toString() ?: "null"
            // Invariant: every scalar precedes scrubbedTrace (trace always
            // last) so hand-rolled header-bounded extractors can never be
            // shadowed by a trace-body literal.
            return "{" +
                "\"id\":" + q(id) + "," +
                "\"exitTimestamp\":" + num(exitTimestamp) + "," +
                "\"capturedAtUtc\":" + capturedAtUtc + "," +
                "\"handlerWriteTime\":" + num(handlerWriteTime) + "," +
                "\"source\":" + q(source) + "," +
                "\"reason\":" + q(reason) + "," +
                "\"appVersion\":" + q(appVersion) + "," +
                "\"osVersion\":" + q(osVersion) + "," +
                "\"deviceModel\":" + q(deviceModel) + "," +
                "\"abi\":" + q(abi) + "," +
                "\"processName\":" + q(processName) + "," +
                "\"pid\":" + pid + "," +
                "\"truncated\":" + (if (truncated) "true" else "false") + "," +
                "\"rssKb\":" + rssKb + "," +
                "\"pssKb\":" + pssKb + "," +
                "\"scrubbedTrace\":" + q(scrubbedTrace) +
                "}"
        }

        /**
         * Drop-in [toJson] that shrinks the trace so the result normally fits
         * within [maxBytes] UTF-8 bytes, keeping header fields intact and the
         * payload valid JSON (never a mid-string cut).
         *
         * Only the trace field is ever shrunk — header fields stay intact —
         * and the `truncated` flag is set when shrinking happens here (OR-ed
         * with the caller's upstream flag). JSON escaping expands (control
         * bytes become 6-char unicode escapes), so the payload is re-measured after
         * each shrink; every round removes at least the measured excess
         * (escaping only ever expands), so this converges immediately.
         * Headers alone exceeding [maxBytes] (or 8 guard rounds) are returned
         * as-is — the "guarantee" is trace-bounded, not absolute.
         */
        @JvmStatic
        @JvmOverloads
        fun toJsonCapped(
            id: String,
            exitTimestamp: Long?,
            capturedAtUtc: Long,
            handlerWriteTime: Long?,
            source: String,
            reason: String,
            appVersion: String,
            osVersion: String,
            deviceModel: String,
            abi: String,
            processName: String,
            pid: Int,
            truncated: Boolean,
            scrubbedTrace: String,
            rssKb: Long,
            pssKb: Long,
            maxBytes: Int = MAX_FILE_BYTES,
        ): String {
            var trace = scrubbedTrace
            var json = toJson(id, exitTimestamp, capturedAtUtc, handlerWriteTime, source, reason, appVersion, osVersion, deviceModel, abi, processName, pid, truncated, trace, rssKb, pssKb)
            var guard = 0
            var jsonBytes = json.toByteArray(StandardCharsets.UTF_8)
            while (jsonBytes.size > maxBytes && trace.isNotEmpty() && guard++ < 8) {
                val excess = jsonBytes.size - maxBytes
                val rawBytes = trace.toByteArray(StandardCharsets.UTF_8)
                // Capped at a valid UTF-8 boundary, never splitting a
                // multi-byte sequence (split trailing bytes decode away).
                trace = String(rawBytes, 0, (rawBytes.size - excess - 32).coerceAtLeast(0), StandardCharsets.UTF_8)
                json = toJson(id, exitTimestamp, capturedAtUtc, handlerWriteTime, source, reason, appVersion, osVersion, deviceModel, abi, processName, pid, true, trace, rssKb, pssKb)
                jsonBytes = json.toByteArray(StandardCharsets.UTF_8)
            }
            return json
        }

        /**
         * Bounded display preview of a share payload: the first [maxLines]
         * lines within [maxChars] chars. Returns the preview plus the
         * omitted line count (0 when nothing was cut).
         *
         * The detail dialog renders this instead of the full trace — laying
         * out 100KB+ tombstones in a TextView janks — while Share still
         * sends the complete captured report.
         */
        @JvmStatic
        @JvmOverloads
        fun previewForDisplay(
            text: String,
            maxLines: Int = 100,
            maxChars: Int = 12_000,
        ): Pair<String, Int> {
            require(maxLines > 0) { "maxLines must be positive" }
            require(maxChars > 0) { "maxChars must be positive" }
            // Count lines without splitting the whole payload: a 100KB+
            // tombstone split into a List<String> on the UI thread is the
            // jank this preview exists to avoid.
            var totalLines = 0
            var idx = 0
            while (true) {
                totalLines++
                val nl = text.indexOf('\n', idx)
                if (nl < 0) break
                idx = nl + 1
            }
            // Trailing '\n' leaves an empty split tail that is not a line.
            if (text.endsWith('\n')) {
                totalLines = (totalLines - 1).coerceAtLeast(0)
            }
            if (totalLines <= maxLines && text.length <= maxChars) {
                // Strip a single trailing newline for display parity; it is
                // not counted as an omitted line.
                return Pair(if (text.endsWith('\n')) text.dropLast(1) else text, 0)
            }
            val sb = StringBuilder()
            var used = 0
            var shown = 0
            var start = 0
            while (shown < maxLines && start < text.length) {
                val nl = text.indexOf('\n', start)
                val end = if (nl < 0) text.length else nl
                val len = end - start
                if (used + len + (if (shown > 0) 1 else 0) > maxChars) break
                if (shown > 0) {
                    sb.append('\n')
                    used += 1
                }
                sb.append(text, start, end)
                used += len
                shown += 1
                if (nl < 0) break
                start = nl + 1
            }
            // A single leading line longer than maxChars still shows
            // something rather than an empty preview.
            if (shown == 0 && totalLines > 0) {
                val firstEnd = text.indexOf('\n').let { if (it < 0) text.length else it }
                sb.append(text, 0, minOf(firstEnd, maxChars))
                shown = 1
            }
            return Pair(sb.toString(), (totalLines - shown).coerceAtLeast(0))
        }

        /** Minimal tolerant field extractor; returns null on corrupt input. */
        @JvmStatic
        fun parseSummary(file: File): Entry? {
            return try {
                if (!file.isFile || file.length() > MAX_FILE_BYTES + SIZE_SLOP_BYTES) {
                    return null
                }
                val text = file.readText(StandardCharsets.UTF_8)
                if (!text.startsWith("{") || !text.contains("\"id\"")) {
                    return null
                }
                // Bound field search to the header before scrubbedTrace so a
                // trace body containing a literal can never shadow a header.
                val headerEnd = text.indexOf("\"scrubbedTrace\"")
                val header = if (headerEnd >= 0) text.substring(0, headerEnd) else text
                fun strField(name: String): String? {
                    val key = "\"" + name + "\":\""
                    val i = header.indexOf(key)
                    if (i < 0) {
                        return null
                    }
                    var j = i + key.length
                    val sb = StringBuilder()
                    var closed = false
                    while (j < header.length) {
                        val c = header[j]
                        if (c == '\\' && j + 1 < header.length) {
                            val n = header[j + 1]
                            when (n) {
                                '"', '\\', '/' -> {
                                    sb.append(n)
                                    j += 2
                                }
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
                                'u' -> {
                                    if (j + 5 < header.length) {
                                        sb.append(header.substring(j + 2, j + 6).toInt(16).toChar())
                                        j += 6
                                    } else {
                                        return null
                                    }
                                }
                                else -> {
                                    sb.append(c)
                                    j++
                                }
                            }
                        } else if (c == '"') {
                            closed = true
                            break
                        } else {
                            sb.append(c)
                            j++
                        }
                    }
                    // A file cut mid-string by write()'s backstop cap yields
                    // a truncated id/source/reason — treat as corrupt, skip.
                    if (!closed) return null
                    return sb.toString()
                }
                fun longField(name: String): Long? {
                    val key = "\"" + name + "\":"
                    val i = header.indexOf(key)
                    if (i < 0) {
                        return null
                    }
                    val rest = header.substring(i + key.length).trimStart()
                    if (rest.startsWith("null")) {
                        return null
                    }
                    // Leading '-' only; interior '-' ends the number
                    // (toLongOrNull rejects it, but scan tightly anyway).
                    var end = 0
                    if (rest.startsWith("-")) end = 1
                    while (end < rest.length && rest[end].isDigit()) end++
                    if (end == 0 || (end == 1 && rest.startsWith("-"))) return null
                    return rest.substring(0, end).toLongOrNull()
                }
                val id = strField("id") ?: return null
                // Lenient defaults for forward-compat (new fields); id stays strict.
                val source = strField("source") ?: "unknown"
                val reason = strField("reason") ?: "unknown"
                val exitTs = longField("exitTimestamp")
                val truncated = header.contains("\"truncated\":true")
                Entry(id, file, exitTs, source, reason, truncated)
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun ensureDir(): Boolean {
        return try {
            if (dir.isDirectory) {
                true
            } else {
                dir.mkdirs()
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Atomic write-then-rename (tmp + rename, copy fallback; no fsync —
     * OS buffers are acceptable for a crash backstop).
     *
     * Report payloads should be built with [toJsonCapped] so the result
     * fits: this cap is only a backstop and may split mid-JSON, yielding a
     * file whose tail fields no longer parse. Capped
     * at a valid UTF-8 boundary, never splitting a multi-byte sequence.
     */
    fun write(id: String, json: String): File? {
        return try {
            if (!ensureDir()) {
                return null
            }
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            val capped = if (bytes.size > MAX_FILE_BYTES) {
                // Decode prefix with replacement instead of copying raw bytes,
                // so no invalid trailing multi-byte sequence is stored.
                String(bytes, 0, MAX_FILE_BYTES, StandardCharsets.UTF_8)
                    .toByteArray(StandardCharsets.UTF_8)
            } else {
                bytes
            }
            // Distinct ids map 1:1 in practice (<ts>-<pid>[-pending]);
            // sanitization may theoretically collide, so keep the full id
            // readable and rely on rotation for bounds.
            val safe = sanitizeId(id, 128)
            val tmp = File(dir, "$safe.tmp")
            tmp.writeBytes(capped)
            val dest = File(dir, "$safe.json")
            if (!tmp.renameTo(dest)) {
                // renameTo can fail across volumes; fall back to copy.
                dest.writeBytes(capped)
                try {
                    tmp.delete()
                } catch (_: Throwable) {
                }
            }
            rotate()
            dest
        } catch (_: Throwable) {
            null
        }
    }

    fun list(): List<Entry> {
        return try {
            if (!dir.isDirectory) {
                return emptyList()
            }
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?: return emptyList()
            val entries = ArrayList<Entry>(files.size)
            for (f in files) {
                val e = parseSummary(f)
                if (e != null) {
                    entries.add(e)
                }
            }
            // lastModified granularity is coarse; tie-break on id for determinism.
            entries.sortWith(compareByDescending<Entry> { it.file.lastModified() }.thenByDescending { it.id })
            entries
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun read(entry: Entry): String? {
        if (!isInStore(entry.file)) return null
        return readRaw(entry.file)
    }

    fun readRaw(file: File): String? {
        return try {
            if (!isInStore(file)) return null
            // Bounded: reports are capped at write; refuse absurd files.
            if (!file.isFile || file.length() > MAX_FILE_BYTES + SIZE_SLOP_BYTES) {
                return null
            }
            file.readText(StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    fun delete(entry: Entry): Boolean {
        return try {
            if (!isInStore(entry.file)) return false
            entry.file.delete()
        } catch (_: Throwable) {
            false
        }
    }

    /** True when [file] resolves inside this store dir (Entry forgery guard). */
    fun isInStore(file: File): Boolean {
        return try {
            val root = dir.canonicalPath + File.separator
            file.canonicalPath.startsWith(root)
        } catch (_: Throwable) {
            false
        }
    }

    /** True when a sanitized report id already exists (relaunch dedup). */
    fun exists(id: String): Boolean {
        return try {
            File(dir, "${sanitizeId(id, 128)}.json").isFile
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Deletes all JSON reports (plus tmp/txt backstops in this dir).
     *
     * The `watermark` file is intentionally retained: it prevents
     * re-surfacing already-drained exits. Staged `cacheDir/crash-shares`
     * copies are *not* in this dir — the UI clears them alongside this call.
     */
    fun deleteAll(): Boolean {
        return try {
            if (!dir.isDirectory) {
                return true
            }
            var ok = true
            val files = dir.listFiles() ?: return true
            for (f in files) {
                try {
                    // Also remove staged share payloads (*.txt) so Delete-all
                    // leaves no scrubbed copy behind (privacy).
                    if (f.isFile && (f.name.endsWith(".json") || f.name.endsWith(".tmp") || f.name.endsWith(".txt"))) {
                        if (!f.delete()) {
                            ok = false
                        }
                    }
                } catch (_: Throwable) {
                    ok = false
                }
            }
            ok
        } catch (_: Throwable) {
            false
        }
    }

    fun rotate(): Int {
        return try {
            // Reclaim .tmp orphans from a kill between writeBytes and renameTo.
            try {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") }?.forEach {
                    try {
                        it.delete()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
            val entries = list()
            if (entries.size <= MAX_FILES) {
                return entries.size
            }
            var deleted = 0
            for (i in MAX_FILES until entries.size) {
                try {
                    if (entries[i].file.delete()) {
                        deleted++
                    }
                } catch (_: Throwable) {
                }
            }
            entries.size - deleted
        } catch (_: Throwable) {
            0
        }
    }
}

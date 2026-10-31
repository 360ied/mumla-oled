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
import java.util.Locale

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
        private val SAFE_ID_REGEX = Regex("[^A-Za-z0-9._-]")

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
                    else -> if (c < ' ') {
                        sb.append(String.format(Locale.US, "\\u%04x", c.code))
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
                "\"scrubbedTrace\":" + q(scrubbedTrace) + "," +
                "\"rssKb\":" + rssKb + "," +
                "\"pssKb\":" + pssKb +
                "}"
        }

        /** Minimal tolerant field extractor; returns null on corrupt input. */
        @JvmStatic
        fun parseSummary(file: File): Entry? {
            return try {
                if (!file.isFile || file.length() > MAX_FILE_BYTES + 4096) {
                    return null
                }
                val text = file.readText(StandardCharsets.UTF_8)
                if (!text.startsWith("{") || !text.contains("\"id\"")) {
                    return null
                }
                fun strField(name: String): String? {
                    val key = "\"" + name + "\":\""
                    val i = text.indexOf(key)
                    if (i < 0) {
                        return null
                    }
                    var j = i + key.length
                    val sb = StringBuilder()
                    while (j < text.length) {
                        val c = text[j]
                        if (c == '\\' && j + 1 < text.length) {
                            val n = text[j + 1]
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
                                    if (j + 5 < text.length) {
                                        sb.append(text.substring(j + 2, j + 6).toInt(16).toChar())
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
                            break
                        } else {
                            sb.append(c)
                            j++
                        }
                    }
                    return sb.toString()
                }
                fun longField(name: String): Long? {
                    val key = "\"" + name + "\":"
                    val i = text.indexOf(key)
                    if (i < 0) {
                        return null
                    }
                    val rest = text.substring(i + key.length).trimStart()
                    if (rest.startsWith("null")) {
                        return null
                    }
                    val end = rest.indexOfFirst { !it.isDigit() && it != '-' }
                    val num = if (end < 0) rest else rest.substring(0, end)
                    return num.toLongOrNull()
                }
                val id = strField("id") ?: return null
                // Lenient defaults for forward-compat (new fields); id stays strict.
                val source = strField("source") ?: "unknown"
                val reason = strField("reason") ?: "unknown"
                val exitTs = longField("exitTimestamp")
                // Bounded search: only the header section before scrubbedTrace
                // counts, so a trace body containing the literal never flips it.
                val headerEnd = text.indexOf("\"scrubbedTrace\"")
                val header = if (headerEnd >= 0) text.substring(0, headerEnd) else text
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
     * Callers must pre-truncate the trace field and set `truncated`
     * (see CrashReporter): the [MAX_FILE_BYTES] cap here is only a backstop
     * and may split mid-JSON, yielding a file parseSummary skips. Capped
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
            val safe = id.replace(SAFE_ID_REGEX, "_").take(128).ifEmpty { "report" }
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
        return readRaw(entry.file)
    }

    fun readRaw(file: File): String? {
        return try {
            // Bounded: reports are capped at write; refuse absurd files.
            if (!file.isFile || file.length() > MAX_FILE_BYTES + 4096) {
                return null
            }
            file.readText(StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    fun delete(entry: Entry): Boolean {
        return try {
            entry.file.delete()
        } catch (_: Throwable) {
            false
        }
    }

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

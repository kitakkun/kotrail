package com.kitakkun.kotrail.fir

import java.io.File
import java.security.MessageDigest

/**
 * Records, for every compilation that names an `unloadableDir`, the outbound references the
 * unloadable-code rule looks for (a `ThreadLocal`, a registration with no disposable), whether
 * or not the rule is on there. A compilation that is unloaded with its class loader bundles the
 * modules on its runtime class path, so it reads their main compilations' records
 * (`bundledUnloadableDir`, one per record directory) and
 * reports what they contain: a single switch on the plugin module then covers whatever it
 * bundles, and the set of bundled modules follows the dependency graph rather than a list kept
 * by hand.
 *
 * One record per source file, `<sha1 of the path>.jsonl`, started over whenever the file is
 * compiled: a header naming the file, then one line per finding,
 * `{"kind": "threadLocal", "name": "buffer", "offset": 512}`. A record whose file no longer
 * exists is ignored when read.
 */
object UnloadableRecords {
    class Entry(val file: String, val kind: String, val name: String, val offset: Int)

    private val lock = Any()

    fun begin(directory: String, file: String) {
        synchronized(lock) {
            recordFor(directory, file).apply { parentFile?.mkdirs() }.writeText("{\"file\": ${quote(file)}}\n")
        }
    }

    fun write(directory: String, file: String, kind: String, name: String, offset: Int) {
        val line = "{\"kind\": ${quote(kind)}, \"name\": ${quote(name)}, \"offset\": $offset}\n"
        synchronized(lock) {
            val record = recordFor(directory, file)
            if (!record.isFile) return
            record.appendText(line)
        }
    }

    /** Every entry recorded under [directories], from records whose file still exists. */
    fun read(directories: List<String>): List<Entry> = directories.flatMap { directory ->
        File(directory).listFiles { file -> file.isFile && file.extension == "jsonl" }.orEmpty().sortedBy { it.name }.flatMap { record ->
            val lines = record.readLines().filter { it.isNotBlank() }
            val source = lines.firstOrNull()?.let(::fields)?.get("file") ?: return@flatMap emptyList()
            if (!File(source).isFile) return@flatMap emptyList()
            lines.drop(1).mapNotNull { line ->
                val entry = fields(line)
                Entry(source, entry["kind"] ?: return@mapNotNull null, entry["name"].orEmpty(), entry["offset"]?.toIntOrNull() ?: 0)
            }
        }
    }

    /** `path:line` of an entry, the line counted in the file as it is now. */
    fun location(entry: Entry): String {
        val text = runCatching { File(entry.file).readText() }.getOrNull() ?: return entry.file
        val line = text.take(entry.offset.coerceIn(0, text.length)).count { it == '\n' } + 1
        return "${entry.file}:$line"
    }

    private fun recordFor(directory: String, file: String): File = File(directory, "${sha1(file.toByteArray())}.jsonl")

    private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The scalar fields of one flat JSON object line, as written above. */
    private fun fields(line: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pattern = Regex(""""((?:[^"\\]|\\.)*)"\s*:\s*(?:"((?:[^"\\]|\\.)*)"|(-?\d+))""")
        for (match in pattern.findAll(line)) {
            val key = unescape(match.groupValues[1])
            result[key] = if (match.groupValues[2].isNotEmpty() || match.groupValues[3].isEmpty()) unescape(match.groupValues[2]) else match.groupValues[3]
        }
        return result
    }

    private fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val escaped = value[i + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'u' -> {
                        append(value.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    }
                    else -> append(escaped)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}

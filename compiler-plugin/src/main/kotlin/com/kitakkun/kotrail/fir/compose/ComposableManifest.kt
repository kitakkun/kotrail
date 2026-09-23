package com.kitakkun.kotrail.fir.compose

import java.io.File
import java.security.MessageDigest

/**
 * The UI composables a compilation declares, written by [ComposableManifestChecker] under the
 * directory the `composablesDir` option names and read by the preview-coverage rule of another
 * compilation (`associatedComposablesDir`): a `preview` compilation associated with `main`, a
 * screenshot-test source set. The compilation that has the sources judges what draws
 * ([emitsUi] needs the bodies); the compilation that has the previews sees `main` as class
 * files only, which no symbol provider enumerates.
 *
 * One record per source file, `<sha1 of the path>.jsonl`: a header line naming the file, then
 * one line per composable, `{"name": "com.acme.ui.Card", "package": "com.acme.ui", "visibility": "public"}`.
 * A file's record is rewritten whenever the file is compiled, so with incremental compilation
 * the records of untouched files keep what the build that last saw them found. A record whose
 * file no longer exists is ignored when read.
 */
object ComposableManifest {
    class Entry(val name: String, val packageName: String, val visibility: String)

    private val lock = Any()

    fun write(directory: String, file: String, entries: List<Entry>) {
        val text = buildString {
            append("{\"file\": ").append(quote(file)).append("}\n")
            for (entry in entries) {
                append("{\"name\": ").append(quote(entry.name))
                append(", \"package\": ").append(quote(entry.packageName))
                append(", \"visibility\": ").append(quote(entry.visibility)).append("}\n")
            }
        }
        synchronized(lock) {
            File(directory, "${sha1(file.toByteArray())}.jsonl").apply { parentFile?.mkdirs() }.writeText(text)
        }
    }

    /** Every entry recorded under [directories], from records whose source file still exists. */
    fun read(directories: List<String>): List<Entry> = directories.flatMap { directory ->
        val records = File(directory).listFiles { file -> file.isFile && file.extension == "jsonl" }.orEmpty().sortedBy { it.name }
        records.flatMap { record ->
            val lines = record.readLines().filter { it.isNotBlank() }
            val header = lines.firstOrNull()?.let(::fields) ?: return@flatMap emptyList()
            val source = header["file"] ?: return@flatMap emptyList()
            if (!File(source).isFile) return@flatMap emptyList()
            lines.drop(1).mapNotNull { line ->
                val entry = fields(line)
                val name = entry["name"] ?: return@mapNotNull null
                Entry(name, entry["package"].orEmpty(), entry["visibility"] ?: "public")
            }
        }
    }

    /** The string fields of one flat JSON object line, as written above. */
    private fun fields(line: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        fun readString(): String {
            val out = StringBuilder()
            i++ // opening quote
            while (i < line.length && line[i] != '"') {
                val c = line[i]
                if (c == '\\' && i + 1 < line.length) {
                    when (val escaped = line[i + 1]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            out.append(line.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 4
                        }
                        else -> out.append(escaped)
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            i++ // closing quote
            return out.toString()
        }
        while (i < line.length) {
            if (line[i] != '"') {
                i++
                continue
            }
            val key = readString()
            while (i < line.length && line[i] != '"') i++
            if (i >= line.length) break
            result[key] = readString()
        }
        return result
    }

    private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

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

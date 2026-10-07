package com.kitakkun.kotrail.fir.compose

import java.io.File
import java.security.MessageDigest

/**
 * The UI composables a compilation declares, written by [ComposableManifestChecker] under the
 * directory the `composablesDir` option names and read by the preview-coverage rule of another
 * compilation (`associatedComposablesDir`): a `preview` compilation associated with `main`, a
 * screenshot-test source set. The compilation that has the sources judges what draws
 * ([emitsUi] needs the bodies, or for the callees in files an incremental build did not compile
 * again, the `drawsUi` facts the module's own facts file kept); the compilation that has the
 * previews sees `main` as class files only, which no symbol provider enumerates.
 *
 * One record per source file, `<sha1 of the path>.jsonl`: a header line naming the file by its
 * absolute path, then one line per composable, `{"name": "com.acme.ui.Card", "package": "com.acme.ui", "visibility": "public"}`,
 * and one line per function the file's previews call, `{"callee": "com.acme.ui.Card"}`. Every
 * file the compilation compiles gets a record, one without composables or previews a header
 * alone, and the record is rewritten whenever the file is compiled. With incremental compilation
 * the records of untouched files keep what the build that last compiled them found; that is what
 * lets the preview-coverage rule of the compilation itself count the previews and composables of
 * the files an incremental build does not compile again ([ownRecords]). A record whose file no
 * longer exists is skipped, and deleted by the compilation that owns the directory.
 */
object ComposableManifest {
    class Entry(val name: String, val packageName: String, val visibility: String) {
        /** Whether the composable is declared at the top level of its file, rather than in a class. */
        val isTopLevel: Boolean get() = name.substringBeforeLast('.', "") == packageName
    }

    /** One source file's record: its path, the composables it declares, and what its previews call. */
    class Record(val file: String, val entries: List<Entry>, val previewCallees: Set<String>)

    private val lock = Any()

    fun write(directory: String, file: String, entries: List<Entry>, previewCallees: Collection<String>) {
        val text = buildString {
            append("{\"file\": ").append(quote(file)).append("}\n")
            for (entry in entries) {
                append("{\"name\": ").append(quote(entry.name))
                append(", \"package\": ").append(quote(entry.packageName))
                append(", \"visibility\": ").append(quote(entry.visibility)).append("}\n")
            }
            for (callee in previewCallees.sorted()) {
                append("{\"callee\": ").append(quote(callee)).append("}\n")
            }
        }
        synchronized(lock) {
            File(directory, "${sha1(file.toByteArray())}.jsonl").apply { parentFile?.mkdirs() }.writeText(text)
        }
    }

    /**
     * Every entry recorded under [directories], the record directories of the associated
     * compilations, from records whose source file still exists.
     */
    fun read(directories: List<String>): List<Entry> = directories.flatMap { directory -> records(directory, deleteStale = false).flatMap { it.entries } }

    /**
     * The records under [directory], the compilation's own (`composablesDir`), whose source file
     * still exists. A record whose source file is gone is deleted: nothing will compile that file
     * to rewrite it, and only this compilation writes the directory.
     */
    fun ownRecords(directory: String?): List<Record> = if (directory == null) emptyList() else records(directory, deleteStale = true)

    private fun records(directory: String, deleteStale: Boolean): List<Record> = synchronized(lock) {
        val records = File(directory).listFiles { file -> file.isFile && file.extension == "jsonl" }.orEmpty().sortedBy { it.name }
        records.mapNotNull { record ->
            val lines = record.readLines().filter { it.isNotBlank() }.map(::fields)
            val source = lines.firstOrNull()?.get("file") ?: return@mapNotNull null
            if (!File(source).isFile) {
                if (deleteStale) record.delete()
                return@mapNotNull null
            }
            val body = lines.drop(1)
            val entries = body.mapNotNull { entry ->
                val name = entry["name"] ?: return@mapNotNull null
                Entry(name, entry["package"].orEmpty(), entry["visibility"] ?: "public")
            }
            Record(source, entries, body.mapNotNullTo(HashSet()) { it["callee"] })
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

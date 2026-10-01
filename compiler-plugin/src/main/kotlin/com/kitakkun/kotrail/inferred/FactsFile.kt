package com.kitakkun.kotrail.inferred

import java.io.File

/**
 * The file the inferred facts of a JVM compilation travel in, `<module>.facts`: written where the
 * `factsOut` plugin option says, outside the compilation's output directory, so that neither the
 * class files nor the JAR or AAR built from them carry anything of Kotrail. The Gradle plugin
 * publishes it as a separate artifact (classifier `kotrail-facts`, extension `facts`) and hands
 * the facts files of a compilation's dependencies back to the compiler through the `facts`
 * option.
 *
 * JSON lines: a header, then one line per declaration with at least one fact. The header is
 * always there: a file with the header alone says the module was compiled with Kotrail and has no
 * facts, which a consumer tells apart from a module without a facts file, and which a build can
 * publish like any other.
 *
 * ```
 * {"module": "lib", "kotrail": "0.1.0"}
 * {"file": "lib/src/main/kotlin/Manager.kt", "id": "lib.net/Manager.reconnect", "signature": "kotlin/Int", "facts": {"startsAsyncWork": {}}}
 * ```
 *
 * `file` is the source file relative to the build's root directory (the `rootDir` plugin option,
 * forward slashes), so that the same sources give the same bytes on every machine; it stays
 * absolute when the option is absent or the file lies outside that directory (see [sourcePath]).
 * `id` is the package, a slash, then the class-qualified name (`lib.net/Manager.reconnect` for a
 * member, `lib.net/reconnect` for a top-level callable, `lib.net/Manager` for a class);
 * `signature` tells overloads apart (see [com.kitakkun.kotrail.fir.inferred.FactKeys]). Each
 * fact is the arrays its annotation would carry, keyed by the annotation's parameter names, `{}`
 * for a marker. Lines are sorted by file, then id, then signature, so the same sources give the
 * same bytes.
 */
object FactsFile {
    const val EXTENSION: String = "facts"

    /** One declaration's facts: fact name to parameter name to the strings its annotation array would hold. */
    data class Entry(
        val file: String,
        val id: String,
        val signature: String,
        val facts: Map<String, Map<String, List<String>>>,
    )

    /** `<module>.facts`, with the module name reduced to characters safe in a file name. */
    fun fileName(module: String): String = "${module.replace(Regex("[^A-Za-z0-9._-]"), "_")}.$EXTENSION"

    /**
     * How a source file is named in `file`: relative to [rootDir] with forward slashes when the
     * file lies under it, [path] as given otherwise. The entries a compilation writes and the
     * files it compiled both go through it, so that the merge in [update] matches them.
     */
    fun sourcePath(path: String, rootDir: String?): String {
        val root = rootDir?.let(::File) ?: return path
        val file = File(path)
        return if (file.isAbsolute && file.startsWith(root)) file.relativeTo(root).invariantSeparatorsPath else path
    }

    /** The file on disk that an entry's [file][Entry.file] names: relative ones resolved against [rootDir]. */
    fun sourceFile(file: String, rootDir: String?): File {
        val path = File(file)
        return if (path.isAbsolute || rootDir == null) path else File(rootDir, file)
    }

    /** The file's text: the header and the [entries], merged per declaration and sorted. */
    fun render(module: String, kotrailVersion: String, entries: List<Entry>): String = buildString {
        append("{\"module\": ").append(quote(module)).append(", \"kotrail\": ").append(quote(kotrailVersion)).append("}\n")
        for (entry in normalize(entries)) {
            append("{\"file\": ").append(quote(entry.file))
            append(", \"id\": ").append(quote(entry.id))
            append(", \"signature\": ").append(quote(entry.signature))
            append(", \"facts\": ").append(renderFacts(entry.facts))
            append("}\n")
        }
    }

    /**
     * One declaration's facts as a JSON object, `{"name": {"parameter": ["value", ...]}, ...}`,
     * in the order of the maps given: the `facts` of a line of the file, and the plugin metadata
     * of a declaration (see [PluginMetadata]).
     */
    internal fun renderFacts(facts: Map<String, Map<String, List<String>>>): String =
        facts.entries.joinToString(", ", prefix = "{", postfix = "}") { (name, arguments) ->
            quote(name) + ": {" + arguments.entries.joinToString(", ") { (parameter, values) ->
                quote(parameter) + ": [" + values.joinToString(", ") { quote(it) } + "]"
            } + "}"
        }

    /** The facts of a JSON object such as [renderFacts] writes, or null when [text] is not one. */
    internal fun parseFacts(text: String): Map<String, Map<String, List<String>>>? =
        factsOf(runCatching { Json(text).value() }.getOrNull())

    /** The entries of a file's text; the header and any line that is not an entry are skipped. */
    fun parse(text: String): List<Entry> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
        val fields = runCatching { Json(line).value() }.getOrNull() as? Map<*, *> ?: return@mapNotNull null
        val file = fields["file"] as? String ?: return@mapNotNull null
        val id = fields["id"] as? String ?: return@mapNotNull null
        val signature = fields["signature"] as? String ?: return@mapNotNull null
        val facts = factsOf(fields["facts"]) ?: return@mapNotNull null
        Entry(file, id, signature, facts)
    }.toList()

    /** The facts of a parsed JSON object, leaving out what does not have their shape; null when [value] is not an object. */
    private fun factsOf(value: Any?): Map<String, Map<String, List<String>>>? =
        (value as? Map<*, *>)?.entries?.mapNotNull { (name, arguments) ->
            val parameters = (arguments as? Map<*, *>)?.entries?.mapNotNull { (parameter, values) ->
                val strings = (values as? List<*>)?.filterIsInstance<String>() ?: return@mapNotNull null
                (parameter as? String ?: return@mapNotNull null) to strings
            }?.toMap() ?: return@mapNotNull null
            (name as? String ?: return@mapNotNull null) to parameters
        }?.toMap()

    /**
     * What an incremental compilation leaves in the file: the [existing] entries of the files it
     * did not compile and that still [exist], then the [fresh] entries of the files it did. A
     * recompiled file's old entries go, whatever it says now, and so do a deleted file's.
     */
    fun merge(existing: List<Entry>, fresh: List<Entry>, compiledFiles: Set<String>, exists: (String) -> Boolean): List<Entry> =
        normalize(existing.filter { it.file !in compiledFiles && exists(it.file) } + fresh)

    /**
     * Reads [file], merges [fresh] into it, and writes it back with the header of [module]; the
     * header alone when nothing is left, never deleting the file. [fresh] and [compiledFiles]
     * name files as [sourcePath] does for [rootDir].
     */
    fun update(file: File, module: String, kotrailVersion: String, fresh: List<Entry>, compiledFiles: Set<String>, rootDir: String?) {
        val existing = if (file.isFile) parse(file.readText()) else emptyList()
        val merged = merge(existing, fresh, compiledFiles) { sourceFile(it, rootDir).isFile }
        file.absoluteFile.parentFile.mkdirs()
        file.writeText(render(module, kotrailVersion, merged))
    }

    /** One entry per declaration, the facts of duplicates put together, facts sorted by name, entries sorted by file, id and signature. */
    private fun normalize(entries: List<Entry>): List<Entry> =
        entries.groupBy { Triple(it.file, it.id, it.signature) }
            .map { (key, group) -> Entry(key.first, key.second, key.third, group.flatMap { it.facts.entries }.associate { it.key to it.value }.toSortedMap()) }
            .filter { it.facts.isNotEmpty() }
            .sortedWith(compareBy<Entry>({ it.file }, { it.id }, { it.signature }))

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

    /** Just enough JSON for the lines above: objects, arrays, strings, and literals skipped as null. */
    private class Json(private val text: String) {
        private var index = 0

        fun value(): Any? {
            skipWhitespace()
            return when (text[index]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                else -> literal()
            }
        }

        private fun obj(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            expect('{')
            skipWhitespace()
            if (peek() == '}') return result.also { index++ }
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                expect(':')
                result[key] = value()
                skipWhitespace()
                if (peek() == ',') index++ else break
            }
            skipWhitespace()
            expect('}')
            return result
        }

        private fun array(): List<Any?> {
            val result = ArrayList<Any?>()
            expect('[')
            skipWhitespace()
            if (peek() == ']') return result.also { index++ }
            while (true) {
                result += value()
                skipWhitespace()
                if (peek() == ',') index++ else break
            }
            skipWhitespace()
            expect(']')
            return result
        }

        private fun string(): String {
            expect('"')
            val result = StringBuilder()
            while (true) {
                val c = text[index++]
                when (c) {
                    '"' -> return result.toString()
                    '\\' -> when (val escaped = text[index++]) {
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'u' -> {
                            result.append(text.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> result.append(escaped)
                    }
                    else -> result.append(c)
                }
            }
        }

        private fun literal(): Any? {
            val start = index
            while (index < text.length && text[index] !in ",}] \t") index++
            require(index > start) { "unexpected character at $start" }
            return null
        }

        private fun peek(): Char = text[index]

        private fun expect(c: Char) {
            require(text[index] == c) { "expected '$c' at $index" }
            index++
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }
    }
}

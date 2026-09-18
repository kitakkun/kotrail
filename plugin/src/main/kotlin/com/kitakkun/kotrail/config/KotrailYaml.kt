package com.kitakkun.kotrail.config

/**
 * Reads the subset of YAML a Kotrail configuration needs, without a YAML library: block
 * mappings and sequences, flow sequences and mappings of scalars, plain, quoted and block
 * scalars, comments, and `~`/`null`. Every scalar comes back as a string.
 *
 * What is left out is rejected by name rather than read with a different meaning: anchors and
 * aliases, tags, directives, complex keys, merge keys, several documents, and tab indentation.
 *
 * The parser is line-based: each non-blank, non-comment line is an item of the block whose
 * indentation it has, and a value that continues on the next lines (a nested block, a block
 * scalar) is read by the item that owns it.
 */
class KotrailYaml private constructor(private val file: String, text: String) {
    private class Line(val number: Int, val indent: Int, val content: String)

    private val lines: MutableList<Line> = run {
        var seenContent = false
        text.lines().mapIndexedNotNull { index, raw ->
            val line = raw.trimEnd()
            if (line.isBlank()) return@mapIndexedNotNull null
            val indent = line.indexOfFirst { it != ' ' }
            if (line[indent] == '\t') fail(index + 1, indent + 1, "tabs cannot indent YAML; use spaces")
            val content = line.substring(indent)
            if (content.startsWith("#")) return@mapIndexedNotNull null
            // A document marker before any content is fine; a second document is not.
            if (content == "---" && !seenContent) return@mapIndexedNotNull null
            if (content.startsWith("%")) fail(index + 1, indent + 1, "YAML directives are not supported")
            if (content == "---" || content == "...") fail(index + 1, indent + 1, "a Kotrail configuration is one YAML document")
            seenContent = true
            Line(index + 1, indent, content)
        }.toMutableList()
    }

    private var cursor = 0

    companion object {
        /** The mapping at the top of [text]; an empty file is an empty mapping. */
        fun parse(file: String, text: String): ConfigNode.Mapping {
            val yaml = KotrailYaml(file, text)
            if (yaml.lines.isEmpty()) return ConfigNode.Mapping(LinkedHashMap(), "$file:1:1")
            val root = yaml.readBlock(yaml.lines[0].indent)
            if (yaml.cursor < yaml.lines.size) {
                val line = yaml.lines[yaml.cursor]
                yaml.fail(line.number, line.indent + 1, "unexpected indentation")
            }
            return root as? ConfigNode.Mapping ?: throw ConfigException("$file:1:1: the top level must be a mapping of keys to values")
        }
    }

    private fun at(line: Line, column: Int = line.indent + 1) = "$file:${line.number}:$column"

    private fun fail(number: Int, column: Int, message: String): Nothing =
        throw ConfigException("$file:$number:$column: $message")

    /** A block (mapping or sequence) whose items are indented by [indent]. */
    private fun readBlock(indent: Int): ConfigNode {
        val first = lines[cursor]
        return if (first.content.startsWith("- ") || first.content == "-") readSequence(indent) else readMapping(indent)
    }

    private fun readMapping(indent: Int): ConfigNode.Mapping {
        val entries = LinkedHashMap<String, ConfigNode>()
        val positions = HashMap<String, Int>()
        val keyPositions = HashMap<String, String>()
        val start = lines[cursor]
        while (cursor < lines.size) {
            val line = lines[cursor]
            if (line.indent < indent) break
            if (line.indent > indent) fail(line.number, line.indent + 1, "unexpected indentation")
            if (line.content.startsWith("- ") || line.content == "-") fail(line.number, line.indent + 1, "a sequence item where a key was expected")
            val (key, rest, valueColumn) = splitKey(line)
            positions[key]?.let { fail(line.number, line.indent + 1, "'$key' is already set on line $it") }
            positions[key] = line.number
            keyPositions[key] = at(line)
            cursor++
            entries[key] = readValue(rest, line, valueColumn, indent)
        }
        return ConfigNode.Mapping(entries, at(start), keyPositions)
    }

    private fun readSequence(indent: Int): ConfigNode.Sequence {
        val items = mutableListOf<ConfigNode>()
        val start = lines[cursor]
        while (cursor < lines.size) {
            val line = lines[cursor]
            if (line.indent < indent) break
            if (line.indent > indent) fail(line.number, line.indent + 1, "unexpected indentation")
            if (!(line.content.startsWith("- ") || line.content == "-")) fail(line.number, line.indent + 1, "a key where a sequence item was expected")
            val rest = line.content.removePrefix("-").trimStart()
            cursor++
            if (rest.isEmpty()) {
                items += readNestedOrNull(line, indent)
            } else if (looksLikeKey(rest)) {
                // `- key: value` opens a mapping whose items are indented to the key: the line is
                // re-read as that first key, at that indentation.
                val itemIndent = line.indent + (line.content.length - rest.length)
                cursor--
                lines[cursor] = Line(line.number, itemIndent, rest)
                items += readMapping(itemIndent)
            } else {
                items += scalarOrFlow(rest, line, line.indent + 3)
            }
        }
        return ConfigNode.Sequence(items, at(start))
    }

    /** Splits `key: value`, giving the key, what follows the colon, and the column of that. */
    private fun splitKey(line: Line): Triple<String, String, Int> {
        val content = line.content
        val keyEnd: Int
        val key: String
        if (content.startsWith("\"") || content.startsWith("'")) {
            val quote = content[0]
            val close = content.indexOf(quote, 1)
            if (close < 0) fail(line.number, line.indent + 1, "unterminated quoted key")
            key = content.substring(1, close)
            keyEnd = close + 1
        } else {
            if (content.startsWith("? ")) fail(line.number, line.indent + 1, "complex keys are not supported")
            val colon = findKeyColon(content) ?: fail(
                line.number, line.indent + 1,
                if (':' in content) "expected a space after ':'" else "expected 'key: value'",
            )
            key = content.substring(0, colon).trim()
            keyEnd = colon
        }
        val afterKey = content.substring(keyEnd).trimStart()
        if (!afterKey.startsWith(":")) fail(line.number, line.indent + keyEnd + 1, "expected ':' after the key")
        val rest = afterKey.substring(1)
        if (rest.isNotEmpty() && !rest[0].isWhitespace()) fail(line.number, line.indent + keyEnd + 2, "expected a space after ':'")
        if (key.isEmpty()) fail(line.number, line.indent + 1, "empty key")
        if (key == "<<") fail(line.number, line.indent + 1, "merge keys are not supported")
        val valueColumn = line.indent + content.length - rest.trimStart().length + 1
        return Triple(key, stripComment(rest.trim()), valueColumn)
    }

    /** The `:` that ends a plain key: followed by a space or the end of the line, outside quotes. */
    private fun findKeyColon(content: String): Int? {
        var i = 0
        while (i < content.length) {
            val c = content[i]
            if (c == ':' && (i + 1 == content.length || content[i + 1].isWhitespace())) return i
            if (c == '#' && i > 0 && content[i - 1].isWhitespace()) return null
            i++
        }
        return null
    }

    private fun looksLikeKey(text: String): Boolean =
        !text.startsWith("[") && !text.startsWith("{") && !text.startsWith("\"") && !text.startsWith("'") && findKeyColon(text) != null

    /** A value after `key:`: inline, or on the following more-indented lines. */
    private fun readValue(rest: String, line: Line, valueColumn: Int, indent: Int): ConfigNode {
        if (rest.isEmpty()) return readNestedOrNull(line, indent)
        if (rest == "|" || rest == ">" || rest == "|-" || rest == ">-") return readBlockScalar(rest, line, indent)
        return scalarOrFlow(rest, line, valueColumn)
    }

    /** What follows a key or a `-` with nothing on its line: a nested block, or nothing. */
    private fun readNestedOrNull(line: Line, indent: Int): ConfigNode {
        if (cursor < lines.size && lines[cursor].indent > indent) return readBlock(lines[cursor].indent)
        // A sequence directly under a key may sit at the key's own indentation.
        if (cursor < lines.size && lines[cursor].indent == indent && lines[cursor].content.startsWith("- ") && !line.content.startsWith("- ")) {
            return readSequence(indent)
        }
        return ConfigNode.Null(at(line))
    }

    private fun readBlockScalar(indicator: String, line: Line, indent: Int): ConfigNode {
        val collected = mutableListOf<String>()
        var blockIndent = -1
        while (cursor < lines.size && lines[cursor].indent > indent) {
            val l = lines[cursor]
            if (blockIndent < 0) blockIndent = l.indent
            collected += " ".repeat(maxOf(0, l.indent - blockIndent)) + l.content
            cursor++
        }
        val text = if (indicator.startsWith("|")) collected.joinToString("\n") else collected.joinToString(" ")
        return ConfigNode.Scalar(if (indicator.endsWith("-")) text else text + "\n", at(line))
    }

    private fun scalarOrFlow(text: String, line: Line, column: Int): ConfigNode {
        val value = stripComment(text)
        return when {
            value.startsWith("[") -> readFlowSequence(value, line, column)
            value.startsWith("{") -> readFlowMapping(value, line, column)
            else -> scalar(value, line, column)
        }
    }

    private fun scalar(text: String, line: Line, column: Int): ConfigNode {
        val position = "$file:${line.number}:$column"
        return when {
            text.isEmpty() || text == "~" || text == "null" -> ConfigNode.Null(position)
            text.startsWith("\"") -> ConfigNode.Scalar(unquoteDouble(text, line, column), position)
            text.startsWith("'") -> {
                if (!text.endsWith("'") || text.length < 2) fail(line.number, column, "unterminated single-quoted value")
                ConfigNode.Scalar(text.substring(1, text.length - 1).replace("''", "'"), position)
            }
            text.startsWith("&") || text.startsWith("*") -> fail(line.number, column, "anchors and aliases are not supported; a value starting with '${text[0]}' needs quotes")
            text.startsWith("!") -> fail(line.number, column, "a value starting with '!' is a YAML tag; quote it, for example \"!annotated(...)\"")
            text.startsWith("@") || text.startsWith("`") -> fail(line.number, column, "a value starting with '${text[0]}' needs quotes")
            else -> ConfigNode.Scalar(text, position)
        }
    }

    private fun unquoteDouble(text: String, line: Line, column: Int): String {
        if (!text.endsWith("\"") || text.length < 2) fail(line.number, column, "unterminated double-quoted value")
        val body = text.substring(1, text.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                when (val e = body[i + 1]) {
                    'n' -> out.append('\n'); 't' -> out.append('\t'); '"' -> out.append('"'); '\\' -> out.append('\\')
                    'u' -> { out.append(body.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> fail(line.number, column, "unknown escape '\\$e'")
                }
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    private fun readFlowSequence(text: String, line: Line, column: Int): ConfigNode {
        if (!text.endsWith("]")) fail(line.number, column, "a flow sequence must close with ']' on the same line")
        val inner = text.substring(1, text.length - 1)
        val items = splitFlow(inner, line, column).map { (item, col) -> scalar(item, line, col) }
        return ConfigNode.Sequence(items, "$file:${line.number}:$column")
    }

    private fun readFlowMapping(text: String, line: Line, column: Int): ConfigNode {
        if (!text.endsWith("}")) fail(line.number, column, "a flow mapping must close with '}' on the same line")
        val inner = text.substring(1, text.length - 1)
        val entries = LinkedHashMap<String, ConfigNode>()
        for ((item, col) in splitFlow(inner, line, column)) {
            val colon = findKeyColon(item) ?: fail(line.number, col, "expected 'key: value' inside '{ }'")
            val key = item.substring(0, colon).trim().removeSurrounding("\"").removeSurrounding("'")
            val value = item.substring(colon + 1).trim()
            entries[key] = if (value.startsWith("[")) readFlowSequence(value, line, col) else scalar(value, line, col)
        }
        return ConfigNode.Mapping(entries, "$file:${line.number}:$column")
    }

    /** Comma-separated items of a flow collection, with the column of each; nested `[ ]` and quotes are respected. */
    private fun splitFlow(inner: String, line: Line, column: Int): List<Pair<String, Int>> {
        val items = mutableListOf<Pair<String, Int>>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        for (i in inner.indices) {
            val c = inner[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '[' || c == '{' -> depth++
                c == ']' || c == '}' -> depth--
                c == ',' && depth == 0 -> { items += inner.substring(start, i) to column + 1 + start; start = i + 1 }
            }
        }
        if (quote != null) fail(line.number, column, "unterminated quoted value")
        items += inner.substring(start) to column + 1 + start
        return items.map { (item, col) -> item.trim() to col + (item.length - item.trimStart().length) }
            .filter { it.first.isNotEmpty() }
    }

    /** Drops a trailing ` # comment` outside quotes. */
    private fun stripComment(text: String): String {
        var quote: Char? = null
        for (i in text.indices) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> if (i == 0) quote = c
                c == '#' && i > 0 && text[i - 1].isWhitespace() -> return text.substring(0, i).trimEnd()
            }
        }
        return text
    }
}

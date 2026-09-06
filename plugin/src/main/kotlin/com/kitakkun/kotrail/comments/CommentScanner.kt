package com.kitakkun.kotrail.comments

/** A comment found in source text. Offsets are half-open character offsets into the file. */
data class Comment(val kind: CommentKind, val startOffset: Int, val endOffset: Int, val lineCount: Int, val startsLine: Boolean)

enum class CommentKind { LINE, BLOCK, KDOC }

/**
 * Finds comments in Kotlin source without the platform lexer, so that the plugin stays free of
 * IntelliJ platform classes. Handles nested block comments, string and character literals,
 * raw strings, and string templates (`${...}` may contain strings and comments of its own).
 */
object CommentScanner {
    fun scan(text: CharSequence): List<Comment> {
        val comments = mutableListOf<Comment>()
        val length = text.length
        var i = 0
        // Stack of open contexts entered through `${` inside strings: each entry records whether the
        // enclosing string was raw, so the string can be resumed when the template closes.
        val templateStack = ArrayDeque<Boolean>()
        var braceDepth = 0
        val braceDepthAtTemplate = ArrayDeque<Int>()

        fun lineStart(offset: Int): Boolean {
            var j = offset - 1
            while (j >= 0 && text[j] != '\n') {
                if (!text[j].isWhitespace()) return false
                j--
            }
            return true
        }

        fun countLines(start: Int, end: Int): Int {
            var lines = 1
            for (k in start until end) if (text[k] == '\n') lines++
            return lines
        }

        fun scanString(raw: Boolean, from: Int): Int {
            // Returns the offset just past the closing quote, or the offset of `${` (template start).
            var j = from
            while (j < length) {
                val c = text[j]
                when {
                    !raw && c == '\\' -> j += 2
                    c == '$' && j + 1 < length && text[j + 1] == '{' -> return j
                    raw && c == '"' && text.startsWith("\"\"\"", j) -> {
                        // A raw string may end with more than three quotes; the last three close it.
                        var k = j
                        while (k < length && text[k] == '"') k++
                        return k
                    }
                    !raw && c == '"' -> return j + 1
                    else -> j++
                }
            }
            return length
        }

        fun enterString(raw: Boolean, from: Int) {
            val stop = scanString(raw, from)
            if (stop < length && text.startsWith("\${", stop)) {
                templateStack.addLast(raw)
                braceDepthAtTemplate.addLast(braceDepth)
                braceDepth = 0
                i = stop + 2
            } else {
                i = stop
            }
        }

        while (i < length) {
            val c = text[i]
            when {
                c == '/' && i + 1 < length && text[i + 1] == '/' -> {
                    val start = i
                    while (i < length && text[i] != '\n') i++
                    comments += Comment(CommentKind.LINE, start, i, 1, lineStart(start))
                }
                c == '/' && i + 1 < length && text[i + 1] == '*' -> {
                    val start = i
                    val isKDoc = text.startsWith("/**", i) && !text.startsWith("/**/", i)
                    var depth = 1
                    i += 2
                    while (i < length && depth > 0) {
                        when {
                            text.startsWith("/*", i) -> { depth++; i += 2 }
                            text.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                    val kind = if (isKDoc) CommentKind.KDOC else CommentKind.BLOCK
                    comments += Comment(kind, start, i, countLines(start, i), lineStart(start))
                }
                c == '"' -> {
                    val raw = text.startsWith("\"\"\"", i)
                    enterString(raw, if (raw) i + 3 else i + 1)
                }
                c == '\'' -> {
                    i++
                    while (i < length && text[i] != '\'') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    i++
                }
                c == '{' -> { braceDepth++; i++ }
                c == '}' -> {
                    if (braceDepth == 0 && templateStack.isNotEmpty()) {
                        // Closing a template: resume the string it was embedded in.
                        val raw = templateStack.removeLast()
                        braceDepth = braceDepthAtTemplate.removeLast()
                        enterString(raw, i + 1)
                    } else {
                        if (braceDepth > 0) braceDepth--
                        i++
                    }
                }
                else -> i++
            }
        }
        return comments
    }
}

package com.kitakkun.kotrail.comments

/**
 * Decides whether a suppression annotation has a written reason next to it, from the file's
 * text alone, since FIR keeps no comment nodes.
 *
 * A reason is a comment on the annotation's own line (before or after it, `//` or a block), or a
 * comment line directly above it: a `//` line, a block or KDoc line, or the line a block ends on.
 * Any comment text counts; nothing here judges it.
 *
 * A comment above a group counts for the whole group. Walking up from the annotation's line, a
 * line that carries a checked suppression annotation is a group member and the walk goes on,
 * through the member's other annotation lines and the body of a multi-line member (lines
 * indented deeper than the annotation, and the brace that closes them); a blank line is crossed
 * only when the next non-blank line above is such a member or such a closing brace. A line at
 * the annotation's level that carries no annotation is a declaration outside the group, unless
 * the line above it is an annotation line, in which case those annotations decide; the walk
 * stops there, and after [MAX_GROUP_LINES] lines. A comment block that ends within
 * [MAX_BLANK_LINES_BEFORE_COMMENT] blank lines above the annotation, or above the first member
 * of its group, with nothing but blank lines between, counts too: a section comment is often
 * set off by a blank line. A comment further away is out of reach, so it does not count.
 *
 * A file annotation (`@file:`) takes only a comment on its own line or on the line directly
 * above, so a license or file header followed by a blank line is not its reason; it is never a
 * group member, and the walk from a declaration stops at it.
 */
object SuppressionReason {
    private const val MAX_GROUP_LINES = 60
    private const val MAX_BLANK_LINES_BEFORE_COMMENT = 2

    /**
     * [isSuppression] says whether a line of the file carries one of the checked suppression
     * annotations; [annotationStart] and [annotationEnd] are the annotation's offsets in [text].
     */
    fun hasReason(text: CharSequence, annotationStart: Int, annotationEnd: Int, isSuppression: (String) -> Boolean): Boolean {
        val lines = text.split('\n')
        val first = lineIndexOf(lines, annotationStart)
        val last = lineIndexOf(lines, maxOf(annotationStart, annotationEnd - 1))
        for (index in first..last) if (hasComment(lines[index])) return true

        if (text.startsWith("@file:", annotationStart)) return first > 0 && isCommentLine(lines[first - 1])

        val level = indentOf(lines[first])
        var current = first - 1
        var steps = 0
        while (current >= 0 && steps < MAX_GROUP_LINES) {
            val line = lines[current]
            val trimmed = line.trim()
            when {
                isCommentLine(line) -> return true
                trimmed.startsWith("@file:") -> return false
                trimmed.isEmpty() -> {
                    var above = current - 1
                    while (above >= 0 && lines[above].isBlank()) above--
                    if (above < 0) return false
                    if (isCommentLine(lines[above]) && current - above <= MAX_BLANK_LINES_BEFORE_COMMENT) return true
                    if (!isSuppression(lines[above]) && !closesMember(lines[above], level)) return false
                    current = above
                }
                isSuppression(line) -> if (hasComment(line)) return true else current--
                trimmed.startsWith("@") || indentOf(line) > level || closesMember(line, level) -> current--
                current > 0 && lines[current - 1].trim().startsWith("@") -> current--
                else -> return false
            }
            steps++
        }
        return false
    }

    /** The brace or parenthesis that closes a multi-line declaration at the group's level. */
    private fun closesMember(line: String, level: Int): Boolean {
        val trimmed = line.trim()
        return (trimmed.startsWith("}") || trimmed.startsWith(")")) && indentOf(line) <= level
    }

    private fun indentOf(line: String): Int = line.length - line.trimStart().length

    private fun lineIndexOf(lines: List<String>, offset: Int): Int {
        var start = 0
        for ((index, line) in lines.withIndex()) {
            if (offset <= start + line.length) return index
            start += line.length + 1
        }
        return lines.lastIndex
    }

    /** A line that is a comment: `//`, the start of a block or KDoc, its continuation, or its end. */
    private fun isCommentLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
    }

    /** A line with a comment anywhere on it, outside string literals; a block comment continued from above counts too. */
    private fun hasComment(line: String): Boolean =
        line.trimStart().startsWith("*") || CommentScanner.scan(line).isNotEmpty()
}

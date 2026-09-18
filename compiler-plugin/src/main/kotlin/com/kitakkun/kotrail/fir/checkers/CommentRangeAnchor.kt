package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.comments.Comment
import com.kitakkun.kotrail.compat.PLUGIN_GENERATED_SOURCE_KIND
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtSourceElementOffsetStrategy
import org.jetbrains.kotlin.fakeElement

/**
 * Builds fake source elements on the file's source that render at exact offsets.
 *
 * The light-tree positioning strategy shifts a reported range by the distance between the
 * file node and its first/last non-filler child (fillers being whitespace and comments).
 * Those distances are recomputed here from the text and subtracted in advance, so that the
 * final range is the comment itself. The first non-filler child is the package directive,
 * which sits at offset 0 when the file has none, or a `@file:` annotation list or an
 * `import` list when they come first.
 */
internal class CommentRangeAnchor(private val source: KtSourceElement, text: CharSequence, comments: List<Comment>) {
    private val startDelta: Int
    private val endDelta: Int

    init {
        var first = 0
        var commentIndex = 0
        while (first < text.length) {
            if (text[first].isWhitespace()) { first++; continue }
            if (commentIndex < comments.size && comments[commentIndex].startOffset == first) {
                first = comments[commentIndex].endOffset
                commentIndex++
                continue
            }
            break
        }
        val startsWithPreamble = PREAMBLE_KEYWORDS.any { text.startsWith(it, first) }
        startDelta = if (startsWithPreamble) first else 0

        var last = text.length
        var trailing = comments.size - 1
        while (last > 0) {
            if (text[last - 1].isWhitespace()) { last--; continue }
            if (trailing >= 0 && comments[trailing].endOffset == last) {
                last = comments[trailing].startOffset
                trailing--
                continue
            }
            break
        }
        endDelta = last - text.length
    }

    fun elementFor(startOffset: Int, endOffset: Int): KtSourceElement? {
        val start = startOffset - startDelta
        val end = endOffset - endDelta
        if (start > end) return null
        return source.fakeElement(
            PLUGIN_GENERATED_SOURCE_KIND,
            KtSourceElementOffsetStrategy.Custom.Initialized(start, end),
        )
    }

    private companion object {
        val PREAMBLE_KEYWORDS = listOf("package ", "package\n", "import ", "@file")
    }
}

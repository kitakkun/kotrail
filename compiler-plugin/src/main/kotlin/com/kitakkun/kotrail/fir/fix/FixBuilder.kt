package com.kitakkun.kotrail.fir.fix

import com.kitakkun.kotrail.fir.FixEdit
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.text

/**
 * Builds the [FixEdit]s of a diagnostic. A checker says what to replace, insert, delete or move;
 * the builder does the offset arithmetic, the line scanning and the text extraction.
 *
 * The edits that need no source text (replace or delete an element, insert before or after one)
 * are on the companion, so a checker that has no container text still produces them. The edits
 * that read the surrounding source (moving a line, scanning for the previous character) are on
 * an instance built with [over] from a container element whose text holds every offset the
 * checker will pass: the block, the call, the class body or the file. Offsets are always
 * absolute file offsets, as [KtSourceElement] reports them; the builder translates to and from
 * the container's text.
 */
class FixBuilder private constructor(
    /** The text of the container. */
    val text: String,
    /** The file offset of the first character of [text]. */
    private val base: Int,
) {
    /** The file offset of [index] into [text]. */
    fun offsetOf(index: Int): Int = base + index

    /** The character at file offset [offset], or `null` outside the container. */
    fun charAt(offset: Int): Char? = text.getOrNull(offset - base)

    /**
     * The file offset where the whitespace before [offset] on its line begins. For an element
     * that opens its line this is the start of the line, and [indentAt] is its indentation; for
     * one that follows other text on the line the scan stops at that text.
     */
    fun indentStart(offset: Int): Int {
        var index = offset - base
        while (index > 0 && text[index - 1] != '\n' && text[index - 1].isWhitespace()) index--
        return base + index
    }

    /** The whitespace between [indentStart] and [offset]: the indentation of an element that opens its line. */
    fun indentAt(offset: Int): String = text.substring(indentStart(offset) - base, offset - base)

    /**
     * The file offset right after the last non-whitespace character before [offset]: [offset]
     * itself when no whitespace precedes it, the container's start when only whitespace does.
     */
    fun previousSignificantEnd(offset: Int): Int {
        var index = offset - base
        while (index > 0 && text[index - 1].isWhitespace()) index--
        return base + index
    }

    /** The last non-whitespace character before [offset], or `null` when the container has none there. */
    fun previousSignificantChar(offset: Int): Char? = charAt(previousSignificantEnd(offset) - 1)

    /** Replaces the characters from [startOffset] (inclusive) to [endOffset] (exclusive). */
    fun replaceRange(startOffset: Int, endOffset: Int, replacement: String): FixEdit = FixEdit(startOffset, endOffset, replacement)

    /** Replaces the first match of [regex] in the container's text, or `null` when it does not match. */
    fun replaceFirst(regex: Regex, replacement: String): FixEdit? =
        regex.find(text)?.let { match -> replaceRange(offsetOf(match.range.first), offsetOf(match.range.last + 1), replacement) }

    /**
     * Deletes the line of [source]: from the start of the indentation before it (see
     * [indentStart]) through the newline that ends it, when one follows directly. With
     * [trailingBlankLine], a blank line after that newline goes too, so that a declaration set
     * apart by blank lines leaves no double gap.
     */
    fun deleteLines(source: KtSourceElement, trailingBlankLine: Boolean): FixEdit {
        val start = indentStart(source.startOffset)
        var end = source.endOffset - base
        if (text.getOrNull(end) == '\n') {
            end++
            if (trailingBlankLine) {
                var blank = end
                while (blank < text.length && text[blank] != '\n' && text[blank].isWhitespace()) blank++
                if (text.getOrNull(blank) == '\n') end = blank + 1
            }
        }
        return FixEdit(start, base + end, "")
    }

    /**
     * Moves the statement [source] onto its own line right before [anchor], with the anchor's
     * indentation: the statement's line is deleted and its text inserted before the anchor.
     * Empty when the statement has no text.
     */
    fun moveBefore(source: KtSourceElement, anchor: KtSourceElement): List<FixEdit> {
        val moved = textOf(source) ?: return emptyList()
        return listOf(deleteLines(source, trailingBlankLine = false), insertBefore(anchor, "$moved\n${indentAt(anchor.startOffset)}"))
    }

    /**
     * Moves the declaration [source] after [anchor], separated from it by a blank line and
     * indented like it: the declaration's lines and the blank line that follows them are
     * deleted, and its text inserted after the anchor. Empty when the declaration has no text.
     */
    fun moveAfter(source: KtSourceElement, anchor: KtSourceElement): List<FixEdit> {
        val moved = textOf(source) ?: return emptyList()
        return listOf(deleteLines(source, trailingBlankLine = true), insertAfter(anchor, "\n\n${indentAt(anchor.startOffset)}$moved"))
    }

    companion object {
        /** A builder over the text of [container], or `null` when there is no container or it has no text. */
        fun over(container: KtSourceElement?): FixBuilder? {
            val source = container ?: return null
            val text = source.text?.toString() ?: return null
            return FixBuilder(text, source.startOffset)
        }

        /** The text of [source] as written, or `null` when the element has none. */
        fun textOf(source: KtSourceElement?): String? = source?.text?.toString()

        /** Replaces the whole of [source]. */
        fun replace(source: KtSourceElement, replacement: String): FixEdit = FixEdit(source.startOffset, source.endOffset, replacement)

        /** Deletes the whole of [source], and nothing around it. */
        fun delete(source: KtSourceElement): FixEdit = FixEdit(source.startOffset, source.endOffset, "")

        /** Inserts [text] right before [source]. */
        fun insertBefore(source: KtSourceElement, text: String): FixEdit = FixEdit(source.startOffset, source.startOffset, text)

        /** Inserts [text] right after [source]. */
        fun insertAfter(source: KtSourceElement, text: String): FixEdit = FixEdit(source.endOffset, source.endOffset, text)

        /**
         * [text], the written form of [expression], wrapped in parentheses unless the expression
         * cannot rebind when placed as an operand: a qualified access, a literal or a call.
         */
        fun parenthesizeIfNeeded(text: String, expression: FirExpression): String =
            if (expression is FirQualifiedAccessExpression || expression is FirLiteralExpression || expression is FirFunctionCall) text else "($text)"
    }
}

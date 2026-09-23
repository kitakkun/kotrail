package com.kitakkun.kotrail.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FixSelectionTest {
    private fun apply(text: String, fixes: List<Fix>): Pair<String, Int> {
        val selection = selectEdits(fixes)
        var result = text
        for (edit in selection.edits) result = result.substring(0, edit.start) + edit.replacement + result.substring(edit.end)
        return result to selection.skippedFixes
    }

    /** Moves the line [from] (with its newline) to just after the declaration [after], the way narrativeOrder's fix does. */
    private fun move(text: String, from: String, after: String): Fix {
        val start = text.indexOf(from)
        val userEnd = text.indexOf(after) + after.length
        return Fix(listOf(Edit(start, start + from.length, ""), Edit(userEnd, userEnd, "\n" + from.trimEnd('\n'))))
    }

    @Test
    fun `two moves whose deletions touch both apply`() {
        val text = "A;\nB;\nT;\n"
        val (result, skipped) = apply(text, listOf(move(text, "A;\n", "T;"), move(text, "B;\n", "T;")))
        assertEquals("T;\nA;\nB;\n", result)
        assertEquals(0, skipped)
    }

    @Test
    fun `a move that inserts inside a range another move deletes waits for the next round`() {
        // A's first user is B, and B moves after T: A's insertion point lies inside the text B's move
        // deletes. The record made first wins; B's move is left for the next round.
        val text = "A;\nB;\nT;\n"
        val (result, skipped) = apply(text, listOf(move(text, "A;\n", "B;"), move(text, "B;\n", "T;")))
        assertEquals("B;\nA;\nT;\n", result)
        assertEquals(1, skipped)
    }

    @Test
    fun `a fix is never applied in part`() {
        val text = "A;\nB;\nT;\n"
        val overlapping = Fix(listOf(Edit(0, 4, "X"), Edit(9, 9, "Y")))
        val (result, skipped) = apply(text, listOf(move(text, "A;\n", "T;"), overlapping))
        assertEquals("B;\nT;\nA;\n", result)
        assertEquals(1, skipped)
    }

    @Test
    fun `insertions at one offset keep the recorded source order`() {
        val text = "{}"
        val fixes = listOf(Fix(listOf(Edit(1, 1, "a;"))), Fix(listOf(Edit(1, 1, "b;"))))
        assertEquals("{a;b;}", apply(text, fixes).first)
    }
}

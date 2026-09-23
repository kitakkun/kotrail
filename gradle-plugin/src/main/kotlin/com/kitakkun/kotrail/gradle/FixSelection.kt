package com.kitakkun.kotrail.gradle

/** One text edit of a fix, in the offsets of the file as it was compiled. */
internal class Edit(val start: Int, val end: Int, val replacement: String) {
    val isInsertion: Boolean get() = start == end
}

/** The edits of one diagnostic's fix: applied all together, or not at all. */
internal class Fix(val edits: List<Edit>)

/**
 * Chooses which fixes of one file can be applied together and orders their edits for
 * application from the end of the file backwards.
 *
 * A fix is taken whole: a helper moved by narrativeOrder is one deletion and one insertion, and
 * applying one without the other duplicates or loses the helper. Two fixes conflict when an edit
 * of one overlaps an edit of the other by at least one character, or when one inserts strictly
 * inside a range the other replaces. Touching ranges do not conflict: two deletions that share a
 * boundary, or an insertion at the edge of a deletion, apply cleanly from the end backwards. The
 * fixes are considered in the order recorded, so on a conflict the earlier record wins and the
 * later one is left for the next compilation, which sees the text after this round.
 *
 * The edits come back sorted so that applying them in order never shifts an offset a later edit
 * needs: by start descending, then end descending, then reverse record order, which for
 * insertions at one offset (two declarations moved to the top of one branch) keeps the source
 * order of the moved text.
 */
internal fun selectEdits(fixes: List<Fix>): FixSelection {
    val accepted = mutableListOf<IndexedValue<Edit>>()
    var skipped = 0
    var index = 0
    for (fix in fixes) {
        val conflicts = fix.edits.any { edit -> accepted.any { (_, taken) -> edit.conflictsWith(taken) } }
        if (conflicts) {
            skipped++
            continue
        }
        for (edit in fix.edits) accepted += IndexedValue(index++, edit)
    }
    val ordered = accepted.sortedWith(
        compareByDescending<IndexedValue<Edit>> { it.value.start }.thenByDescending { it.value.end }.thenByDescending { it.index },
    )
    return FixSelection(ordered.map { it.value }, skipped)
}

internal class FixSelection(val edits: List<Edit>, val skippedFixes: Int)

private fun Edit.conflictsWith(other: Edit): Boolean = when {
    isInsertion && other.isInsertion -> false
    isInsertion -> other.start < start && start < other.end
    other.isInsertion -> start < other.start && other.start < end
    else -> maxOf(start, other.start) < minOf(end, other.end)
}

package com.kitakkun.kotrail.comments

import com.kitakkun.kotrail.exclude.Glob

/**
 * Which suppressions owe a reason, by the ids they suppress (the annotation's string arguments).
 *
 * A suppression owes one when at least one of its ids matches a glob in [ids] and no glob in
 * [exemptIds]: an annotation whose ids are all exempt is skipped, and one that mixes exempt and
 * non-exempt ids is checked.
 */
class SuppressedIds(ids: List<String>, exemptIds: List<String>) {
    private val checked = ids.map(::Glob)
    private val exempt = exemptIds.map(::Glob)

    fun owesReason(suppressed: Collection<String>): Boolean = suppressed.any(::isChecked)

    private fun isChecked(id: String): Boolean = checked.any { it.matches(id) } && exempt.none { it.matches(id) }
}

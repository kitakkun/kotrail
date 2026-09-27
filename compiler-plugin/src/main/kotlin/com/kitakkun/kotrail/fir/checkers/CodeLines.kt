package com.kitakkun.kotrail.fir.checkers

/** A line that carries code: not blank, not a lone brace, not a comment-only line. */
internal fun isCodeLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed == "{" || trimmed == "}") return false
    if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) return false
    return true
}

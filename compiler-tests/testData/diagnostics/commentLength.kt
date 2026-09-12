// KOTRAIL_CONFIG: rules.commentLength=true
// Default: commentLength.maxLines=5, commentLength.maxKDocLines=0 (unlimited).

// One
// Two
// Three
// Four
// Five: exactly the limit, allowed.
val atLimit = 1

<!KOTRAIL_COMMENT_TOO_LONG!>// One
// Two
// Three
// Four
// Five
// Six: one past the limit, reported once for the whole run.<!>
val pastLimit = 2

// A run is broken by a blank line or by code.
// Two
// Three

// Four
// Five
// Six: separate run, allowed.
val broken = 3

val trailing = 4 // trailing comments never start a line, so they never join a run
// Two
// Three
// Four
// Five
// Six: the trailing comment above does not count, so this run has five lines.
val afterTrailing = 5

<!KOTRAIL_COMMENT_TOO_LONG!>/*
 * a block comment
 * spanning
 * more
 * than
 * five lines
 */<!>
val block = 6

/**
 * KDoc is documentation and has no limit by default,
 * however
 * long
 * it
 * gets
 * here.
 */
val documented = 7

val text = """
    // not a comment: inside a raw string
    // neither
    // is
    // this
    // line
    // or this one
"""

val template = "${listOf(1).map { it /* inline */ }} // still a string"

val url = "https://example.com//not-a-comment"

/* GENERATED_FIR_TAGS: integerLiteral, lambdaLiteral, multilineStringLiteral, propertyDeclaration, stringLiteral */

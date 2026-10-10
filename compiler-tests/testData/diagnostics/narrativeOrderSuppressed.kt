// KOTRAIL_CONFIG: rules.narrativeOrder=warning
// The rule runs as a file checker, and at a severity the project moved it reports the _WARNING
// factory: a suppression by the base name, on the helper or on a class around it, holds all the same.

// Not reported: suppressed on the helper itself.
@Suppress("KOTRAIL_HELPER_BEFORE_FIRST_USE")
private fun early(): Int = 1

fun usesEarly(): Int = early()

// Not reported: suppressed on the class around the helper.
@Suppress("KOTRAIL_HELPER_BEFORE_FIRST_USE")
class Quiet {
    private fun helper(): Int = 1

    fun caller(): Int = helper()
}

// Reported, as a warning: nothing suppresses it.
private fun <!KOTRAIL_HELPER_BEFORE_FIRST_USE_WARNING!>loud<!>(): Int = 2

fun usesLoud(): Int = loud()

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, integerLiteral, stringLiteral */

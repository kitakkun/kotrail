// KOTRAIL_CONFIG: rules.noNotNullAssertion=on, rules.noNotNullAssertion.severity=warning, rules.functionLength=on, rules.functionLength.maxLines=2
// A @Suppress with the base name keeps working after the project changes the rule's severity:
// the diagnostic in use is KOTRAIL_NOT_NULL_ASSERTION_WARNING, and the base name still matches.

// Not reported: suppressed by the base name although the severity was moved to warning.
@Suppress("KOTRAIL_NOT_NULL_ASSERTION")
fun suppressedByBaseName(x: String?): Int = x!!.length

// Not reported: the suffixed name matches as any compiler diagnostic would.
@Suppress("KOTRAIL_NOT_NULL_ASSERTION_WARNING")
fun suppressedBySuffixedName(x: String?): Int = x!!.length

// Reported, at the configured severity.
fun reported(x: String?): Int = <!KOTRAIL_NOT_NULL_ASSERTION_WARNING!>x!!<!>.length

// Not reported: a suppression on the base name of a rule at its default severity.
@Suppress("KOTRAIL_FUNCTION_TOO_LONG")
fun longButSuppressed(): Int {
    val a = 1
    val b = 2
    return a + b
}

fun use() {
    suppressedByBaseName(""); suppressedBySuffixedName(""); reported(""); longButSuppressed()
}

/* GENERATED_FIR_TAGS: additiveExpression, checkNotNullCall, functionDeclaration, integerLiteral, localProperty,
nullableType, propertyDeclaration, stringLiteral */

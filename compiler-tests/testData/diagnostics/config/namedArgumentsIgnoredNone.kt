// KOTRAIL_CONFIG: configFile=compiler-tests/testData/diagnostics/config/namedArgumentsIgnoredNone.yaml, rules.namedArgumentsForRepeatedTypes=on
// An empty ignoredFunctions, in namedArgumentsIgnoredNone.yaml next to this file, leaves no function alone:
// maxOf and minOf, which the default list skips, are reported like any other call.
fun reported(peak: Double, left: Double, low: Int, right: Int) {
    <!KOTRAIL_NAMED_ARGUMENTS_REQUIRED!>maxOf(1.0, peak, left)<!>
    <!KOTRAIL_NAMED_ARGUMENTS_REQUIRED!>minOf(low, right, 0)<!>
}

/* GENERATED_FIR_TAGS: functionDeclaration, integerLiteral */

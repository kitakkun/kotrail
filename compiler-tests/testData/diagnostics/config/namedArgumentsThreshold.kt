// KOTRAIL_CONFIG: rules.namedArgumentsForRepeatedTypes=true, namedArgumentsForRepeatedTypes.minArguments=2
// With the threshold lowered to 2, a pair of positional arguments of one type already needs names.
fun pair(x: Int, y: Int) {}
fun two(count: Int, label: String) {}

fun reported() {
    <!NAMED_ARGUMENTS_REQUIRED!>pair(1, 2)<!>
}

fun quiet() {
    pair(1, y = 2)
    pair(x = 1, y = 2)
    two(1, "one")
}

/* GENERATED_FIR_TAGS: functionDeclaration, integerLiteral, stringLiteral */

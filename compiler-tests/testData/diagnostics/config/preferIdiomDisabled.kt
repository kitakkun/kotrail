// KOTRAIL_CONFIG: rules.preferIdiom=on, rules.preferIdiom.disabled=chain, elvis
// Disabled idioms are not asked for; the others still are.
fun examples(items: List<Int>, maybe: String?, fallback: String) {
    val first = items.filter { it > 1 }.first()
    val name = if (maybe != null) maybe else fallback
    if (<!KOTRAIL_PREFER_IDIOM!>items.size == 0<!>) println("empty")
    println("$first $name")
}

/* GENERATED_FIR_TAGS: comparisonExpression, equalityExpression, functionDeclaration, ifExpression, integerLiteral,
lambdaLiteral, localProperty, nullableType, propertyDeclaration, smartcast, stringLiteral */

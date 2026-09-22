// KOTRAIL_CONFIG: rules.preferIdiom=on, rules.preferIdiom.chains=query.Query.where then query.Query.single -> query.Query.singleWhere, rules.preferIdiom.calls=kotlin.collections.getOrNull(0) -> kotlin.collections.firstOrNull
// A project's own chain and call idioms, matched and checked by fully qualified name.
package query

class Query(val values: List<Int>) {
    fun where(predicate: (Int) -> Boolean): Query = Query(values.filter(predicate))
    fun where(predicate: (Int) -> Boolean, extra: Int): Query = this
    fun single(): Int = values.single()
    fun singleWhere(predicate: (Int) -> Boolean): Int = values.single(predicate)
}

fun examples(query: Query, items: List<Int>) {
    // Reported: the configured chain, with the lambda carried over.
    val one = <!KOTRAIL_PREFER_IDIOM!>query.where { it > 1 }.single()<!>

    // Reported: the configured call with its literal argument.
    val head = <!KOTRAIL_PREFER_IDIOM!>items.getOrNull(0)<!>

    // Not reported: a different literal, or a where with more arguments.
    val second = items.getOrNull(1)
    val other = query.where({ it > 1 }, 2).single()

    println("$one $head $second $other")
}

/* GENERATED_FIR_TAGS: classDeclaration, comparisonExpression, functionDeclaration, functionalType, integerLiteral,
lambdaLiteral, localProperty, nullableType, primaryConstructor, propertyDeclaration, stringLiteral, thisExpression */

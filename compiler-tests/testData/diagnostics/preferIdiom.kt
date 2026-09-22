// KOTRAIL_CONFIG: rules.preferIdiom=on
class Box(val size: Int)

fun examples(items: List<Int>, words: Set<String>, text: String, map: Map<String, Int>, array: Array<Int>, box: Box, maybe: String?, fallback: String) {
    // Reported (emptiness): a size or length compared with zero.
    if (<!KOTRAIL_PREFER_IDIOM!>items.size == 0<!>) println("empty")
    if (<!KOTRAIL_PREFER_IDIOM!>words.size != 0<!>) println("some")
    if (<!KOTRAIL_PREFER_IDIOM!>text.length == 0<!>) println("blank")
    if (<!KOTRAIL_PREFER_IDIOM!>map.size > 0<!>) println("entries")
    if (<!KOTRAIL_PREFER_IDIOM!>array.size >= 1<!>) println("entries")
    if (<!KOTRAIL_PREFER_IDIOM!>items.count() == 0<!>) println("empty")

    // Not reported: `size` of a class that is not a collection, or compared with another number.
    if (box.size == 0) println("no box")
    if (items.size == 2) println("pair")

    // Reported (negation): a negated emptiness or blankness check.
    if (<!KOTRAIL_PREFER_IDIOM!>!items.isEmpty()<!>) println("some")
    if (<!KOTRAIL_PREFER_IDIOM!>!text.isBlank()<!>) println("text")

    // Reported (nullOrEmpty): a null check joined to an emptiness check of the same value.
    if (<!KOTRAIL_PREFER_IDIOM!>maybe == null || maybe.isEmpty()<!>) println("nothing")
    if (<!KOTRAIL_PREFER_IDIOM!>maybe == null || maybe.isBlank()<!>) println("nothing")

    // Not reported: different values, or another operator.
    if (maybe == null || text.isEmpty()) println("mixed")
    if (maybe != null && maybe.isNotEmpty()) println("some")

    // Reported (chain): a filter followed by a terminal that takes the predicate itself.
    val first = <!KOTRAIL_PREFER_IDIOM!>items.filter { it > 1 }.first()<!>
    val found = <!KOTRAIL_PREFER_IDIOM!>items.filter { it > 1 }.firstOrNull()<!>
    val hasBig = <!KOTRAIL_PREFER_IDIOM!>items.filter { it > 1 }.isNotEmpty()<!>
    val bigCount = <!KOTRAIL_PREFER_IDIOM!>items.filter { it > 1 }.size<!>
    val lengths = <!KOTRAIL_PREFER_IDIOM!>words.map { it.length.takeIf { n -> n > 2 } }.filterNotNull()<!>

    // Not reported: a filter that feeds something else.
    val doubled = items.filter { it > 1 }.map { it * 2 }

    // Reported (elvis): a null check that returns the value itself.
    val name = <!KOTRAIL_PREFER_IDIOM!>if (maybe != null) maybe else fallback<!>

    // Not reported: the branches do something else.
    val length = if (maybe != null) maybe.length else 0

    println("$first $found $hasBig $bigCount $lengths $doubled $name $length")
}

/* GENERATED_FIR_TAGS: andExpression, classDeclaration, comparisonExpression, disjunctionExpression, equalityExpression,
functionDeclaration, ifExpression, integerLiteral, lambdaLiteral, localProperty, multiplicativeExpression, nullableType,
primaryConstructor, propertyDeclaration, smartcast, stringLiteral */

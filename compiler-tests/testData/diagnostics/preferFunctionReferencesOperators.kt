// KOTRAIL_CONFIG: rules.preferFunctionReferences=on
// DUMP_KOTRAIL_FIXES
// A lambda written with operator syntax names no function, and the operator reads better than the
// function behind it: `{ -it }` over `Int::unaryMinus`. The same functions called by name, and an
// infix function called by its name, are still asked for.

class Vec(val x: Int) {
    operator fun unaryMinus(): Vec = Vec(-x)
    operator fun plus(other: Vec): Vec = Vec(x + other.x)
    operator fun compareTo(other: Vec): Int = x - other.x
    operator fun rangeTo(other: Vec): List<Vec> = listOf(this, other)
    operator fun get(index: Int): Int = x + index
    operator fun contains(value: Int): Boolean = value == x
}

class Table {
    operator fun set(key: Int, value: Int) {}
}

class Counter {
    operator fun plusAssign(step: Int) {}
}

fun slideInVertically(initialOffsetY: (Int) -> Int): Int = initialOffsetY(1)

fun operators(vecs: List<Vec>, ints: List<Int>, flags: List<Boolean>, vec: Vec, table: Table, counter: Counter) {
    // Not reported: prefix operators.
    slideInVertically { -it }
    vecs.map { -it }
    flags.map { !it }

    // Not reported: binary operators, a range included.
    vecs.reduce { a, b -> a + b }
    vecs.zip(vecs) { a, b -> a..b }

    // Not reported: `in`, indexing and an augmented assignment.
    ints.filter { it in vec }
    ints.map { vec[it] }
    ints.forEach { counter += it }

    // Not reported either way: a comparison, an equality, `!in` and an indexed assignment are not a lone forwarding call.
    vecs.zip(vecs) { a, b -> a < b }
    ints.zip(ints) { a, b -> a == b }
    ints.filter { it !in vec }
    ints.zip(ints) { key, value -> table[key] = value }

    // Reported: the same functions called by name.
    vecs.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.unaryMinus() }<!>
    vecs.reduce <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ a, b -> a.plus(b) }<!>
    ints.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ vec.get(it) }<!>

    // Reported: an infix call written with its name.
    ints.zip(ints) <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ a, b -> a shl b }<!>
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, classDeclaration, comparisonExpression, equalityExpression,
functionDeclaration, functionalType, integerLiteral, lambdaLiteral, operator, primaryConstructor, propertyDeclaration,
rangeExpression, thisExpression, unaryExpression */

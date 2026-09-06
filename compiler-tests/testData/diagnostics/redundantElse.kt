sealed interface Shape
class Circle(val radius: Double) : Shape
class Square(val side: Double) : Shape
data object Empty : Shape

enum class Color { RED, GREEN, BLUE }

// Reported: every sealed subclass is covered by `is` checks and object equality.
fun area(shape: Shape): Double = when (shape) {
    is Circle -> shape.radius * shape.radius * 3.14
    is Square -> shape.side * shape.side
    Empty -> 0.0
    <!REDUNDANT_ELSE_IN_EXHAUSTIVE_WHEN!><!REDUNDANT_ELSE_IN_WHEN!>else<!> -> -1.0<!>
}

// Reported: every enum entry is listed.
fun code(color: Color): Int = when (color) {
    Color.RED -> 0
    Color.GREEN -> 1
    Color.BLUE -> 2
    <!REDUNDANT_ELSE_IN_EXHAUSTIVE_WHEN!><!REDUNDANT_ELSE_IN_WHEN!>else<!> -> -1<!>
}

// Reported: both booleans are listed.
fun label(flag: Boolean): String = when (flag) {
    true -> "on"
    false -> "off"
    <!REDUNDANT_ELSE_IN_EXHAUSTIVE_WHEN!><!REDUNDANT_ELSE_IN_WHEN!>else<!> -> "?"<!>
}

// Reported: nullable subject with `null` and all entries covered.
fun nullableCode(color: Color?): Int = when (color) {
    Color.RED, Color.GREEN -> 0
    Color.BLUE -> 1
    null -> 2
    <!REDUNDANT_ELSE_IN_EXHAUSTIVE_WHEN!><!REDUNDANT_ELSE_IN_WHEN!>else<!> -> 3<!>
}

// Reported: a `when` used as a statement is treated the same way.
fun describe(shape: Shape) {
    when (shape) {
        is Circle -> println("circle")
        is Square -> println("square")
        is Empty -> println("empty")
        <!REDUNDANT_ELSE_IN_EXHAUSTIVE_WHEN!><!REDUNDANT_ELSE_IN_WHEN!>else<!> -> println("unknown")<!>
    }
}

// Not reported: a sealed subclass is missing, so `else` does real work.
fun partialArea(shape: Shape): Double = when (shape) {
    is Circle -> shape.radius
    is Square -> shape.side
    else -> 0.0
}

// Not reported: an enum entry is missing.
fun partialCode(color: Color): Int = when (color) {
    Color.RED -> 0
    Color.GREEN -> 1
    else -> 2
}

// Not reported: the nullable subject still needs `else` for `null`.
fun nullableWithoutNull(color: Color?): Int = when (color) {
    Color.RED -> 0
    Color.GREEN -> 1
    Color.BLUE -> 2
    else -> 3
}

// Not reported: no subject.
fun sign(n: Int): String = when {
    n < 0 -> "negative"
    n > 0 -> "positive"
    else -> "zero"
}

// Not reported: the subject type is not sealed, enum, or Boolean.
fun small(n: Int): Boolean = when (n) {
    0, 1, 2 -> true
    else -> false
}

// Not reported: `else` is the only branch.
fun always(color: Color): Int = when (color) {
    else -> 1
}

// Not reported: exhaustive without `else`.
fun sides(shape: Shape): Int = when (shape) {
    is Circle -> 0
    is Square -> 4
    Empty -> 0
}

/* GENERATED_FIR_TAGS: classDeclaration, comparisonExpression, data, disjunctionExpression, enumDeclaration, enumEntry,
equalityExpression, functionDeclaration, integerLiteral, interfaceDeclaration, isExpression, multiplicativeExpression,
nullableType, objectDeclaration, primaryConstructor, propertyDeclaration, sealed, smartcast, stringLiteral,
unaryExpression, whenExpression, whenWithSubject */

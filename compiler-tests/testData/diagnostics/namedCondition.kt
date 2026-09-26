// KOTRAIL_CONFIG: rules.namedCondition=on
// The index is kept per module in the order files are checked: the first copy stays quiet and later copies name it.

// FILE: Node.kt
class Node(val visible: Boolean, val children: List<Node>)

class Options(val includeInvisible: Boolean)

sealed interface Shape
class Circle : Shape
class Square : Shape
class Line : Shape

fun measure(node: Node, options: Options): Int {
    if (!node.visible && !options.includeInvisible && node.children.isEmpty()) return 0
    return 1
}

fun draw(node: Node, options: Options) {
    if (<!KOTRAIL_CONDITION_DUPLICATED!>!node.visible && !options.includeInvisible && node.children.isEmpty()<!>) return
    println("draw")
}

fun submit(a: Boolean, b: Boolean, c: Boolean, d: Boolean) {
    if (<!KOTRAIL_CONDITION_TOO_COMPOUND!>a && b && c && d<!>) println("submit")
}

fun mixed(a: Boolean, b: Boolean, c: Boolean) {
    if (<!KOTRAIL_CONDITION_TOO_COMPOUND!>a && b || c<!>) println("mixed")
    if ((a && b) || c) println("parenthesized")
}

fun loops(a: Boolean, b: Boolean, c: Boolean, d: Boolean) {
    while (<!KOTRAIL_CONDITION_TOO_COMPOUND!>a || b || c || d<!>) break
    when {
        <!KOTRAIL_CONDITION_TOO_COMPOUND!>a && b && !(c || d)<!> -> println("when")
        else -> Unit
    }
}

fun named(a: Boolean, b: Boolean, c: Boolean, d: Boolean) {
    val canSubmit = a && b && c && d
    if (canSubmit) println("named")
}

fun twoOperators(a: Boolean, b: Boolean, c: Boolean) {
    if (a && b && c) println("two operators")
}

fun nullGuard(x: String?, y: Boolean) {
    if (x != null && x.isEmpty() && y) println("guarded")
}

fun subject(shape: Shape) {
    when (shape) {
        is Circle, is Square -> println("round or square")
        is Line -> Unit
    }
}

fun preconditions(a: Boolean, b: Boolean, c: Boolean, d: Boolean) {
    require(a && b && c && d)
}

// FILE: Layout.kt
fun layout(node: Node, options: Options) {
    if (<!KOTRAIL_CONDITION_DUPLICATED!>!node.visible && !options.includeInvisible /* skipped */ && node.children.isEmpty()<!>) return
    println("layout")
}

/* GENERATED_FIR_TAGS: andExpression, break, classDeclaration, disjunctionExpression, equalityExpression,
functionDeclaration, ifExpression, integerLiteral, interfaceDeclaration, isExpression, localProperty, nullableType,
primaryConstructor, propertyDeclaration, sealed, smartcast, stringLiteral, whenExpression, whenWithSubject, whileLoop */

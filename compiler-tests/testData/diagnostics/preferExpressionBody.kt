// KOTRAIL_CONFIG: rules.preferExpressionBody=true
data class Item(val name: String, val price: Int)

// Reported: the block body is a single `return` of an expression.
fun <!PREFER_EXPRESSION_BODY!>total<!>(items: List<Item>): Int {
    return items.sumOf { it.price * 2 }
}

// Reported: an explicit return type is fine with an expression body too.
fun <!PREFER_EXPRESSION_BODY!>label<!>(count: Int): String {
    return "count=$count"
}

// Reported: an extension function with a single return.
fun String.<!PREFER_EXPRESSION_BODY!>shout<!>(): String {
    return uppercase()
}

// Reported: local functions are checked like any other.
fun outer(values: List<Int>): Int {
    fun <!PREFER_EXPRESSION_BODY!>doubled<!>(v: Int): Int {
        return v * 2
    }
    return values.sumOf(::doubled)
}

// Reported: overrides are not exempt; the body shape is the concern, not the signature.
interface Namer {
    fun name(item: Item): String
}

class DefaultNamer : Namer {
    override fun <!PREFER_EXPRESSION_BODY!>name<!>(item: Item): String {
        return "item-${item.price}"
    }
}

// Not reported: already an expression body.
fun cheapest(items: List<Item>): Item? = items.minByOrNull { it.price * 2 }

// Not reported: more than one statement.
fun logged(items: List<Item>): Int {
    println(items.size)
    return items.size
}

// Not reported: the `return` sits inside an `if`, not directly in the body.
fun clamp(v: Int): Int {
    if (v < 0) {
        return 0
    }
    return v
}

// Not reported: a bare `return` in a Unit function.
fun noop() {
    return
}

// Not reported: empty body.
fun nothing() {
}

// Not reported: property accessors are not named functions.
val answer: Int
    get() {
        return 42
    }

/* GENERATED_FIR_TAGS: callableReference, classDeclaration, comparisonExpression, data, funWithExtensionReceiver,
functionDeclaration, getter, ifExpression, integerLiteral, interfaceDeclaration, lambdaLiteral, localFunction,
multiplicativeExpression, nullableType, override, primaryConstructor, propertyDeclaration, stringLiteral */

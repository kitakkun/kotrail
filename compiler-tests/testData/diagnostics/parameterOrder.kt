// KOTRAIL_CONFIG: rules.parameterOrder=on
class Row(val value: Int)
class Item(val value: Int)

typealias Handler = (Int) -> Unit

// Reported: the callback comes before the data it is called with.
fun snackbarAction(<!KOTRAIL_CALLBACK_BEFORE_DATA_PARAMETER!>onClick: () -> Unit<!>, label: String) {
    println(label)
    onClick()
}

// Reported: a constructor is a parameter list too.
class Poller(<!KOTRAIL_CALLBACK_BEFORE_DATA_PARAMETER!>val onTick: (Long) -> Unit<!>, val intervalMs: Long)

// Reported once, on the first offending parameter: suspend, nullable and aliased function types count.
fun load(<!KOTRAIL_CALLBACK_BEFORE_DATA_PARAMETER!>transform: suspend (Row) -> Item<!>, onError: ((Throwable) -> Unit)?, rows: List<Row>, limit: Int) {
    println(rows.size + limit)
}

fun aliased(<!KOTRAIL_CALLBACK_BEFORE_DATA_PARAMETER!>handler: Handler<!>, count: Int) {
    handler(count)
}

// Not reported: data first, then the functions, in whatever order.
fun loadWell(rows: List<Row>, limit: Int, transform: (Row) -> Item, onDone: () -> Unit) {
    println(rows.size + limit)
    onDone()
}

// Not reported: a defaulted callback sits with the other optional parameters; a defaulted data
// parameter does not need a callback after it.
fun optional(onError: (Throwable) -> Unit = {}, label: String = "", onClick: () -> Unit, retries: Int = 3) {
    println(label + retries)
    onClick()
}

// Not reported: fewer than two parameters, or no function-typed one.
fun single(onClick: () -> Unit) = onClick()
fun plain(a: Int, b: String) = println(a.toString() + b)

// Reported on the interface, which defines the order; not on the override, which follows it.
interface Listener {
    fun onEvent(<!KOTRAIL_CALLBACK_BEFORE_DATA_PARAMETER!>handler: (Int) -> Unit<!>, id: Int)
}

class DefaultListener : Listener {
    override fun onEvent(handler: (Int) -> Unit, id: Int) = handler(id)
}

// Not reported: lambdas are out of scope.
val callback: ((Int) -> Unit, Int) -> Unit = { handler, id -> handler(id) }

// Reported: a function type inside a type argument makes the parameter a callback too.
fun banner(<!KOTRAIL_CALLBACK_BEFORE_DATA_PARAMETER!>dismiss: Pair<() -> Unit, String>?<!>, title: String) {
    println(title + dismiss?.second)
}

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, functionDeclaration, functionalType, integerLiteral,
interfaceDeclaration, lambdaLiteral, nullableType, override, primaryConstructor, propertyDeclaration, stringLiteral,
suspend, typeAliasDeclaration */

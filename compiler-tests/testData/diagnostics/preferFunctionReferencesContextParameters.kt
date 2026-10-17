// LANGUAGE: +ContextParameters
// KOTRAIL_CONFIG: rules.preferFunctionReferences=on
// The compiler rejects a callable reference to a declaration with context parameters, so a lambda
// that forwards to one stays as it is.

interface Logger {
    fun log(message: String)
}

class Item(val id: Long) {
    context(logger: Logger)
    fun process() = logger.log("$id")
}

context(logger: Logger)
fun record(item: Item) = logger.log("${item.id}")

context(logger: Logger)
fun Item.archive() = logger.log("$id")

context(logger: Logger)
val Item.label: String get() = "$id"

fun plain(item: Item) {}

class Recorder {
    context(logger: Logger)
    fun save(item: Item) = logger.log("${item.id}")
}

context(logger: Logger)
fun run(items: List<Item>, recorder: Recorder) {
    // Not reported: `::record`, `Item::archive`, `Item::process`, `recorder::save` and `Item::label`
    // do not compile.
    items.forEach { record(it) }
    items.forEach { it.archive() }
    items.forEach { it.process() }
    items.forEach { recorder.save(it) }
    items.map { it.label }

    // Reported: a callee without context parameters, even inside a function with them.
    items.forEach <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ plain(it) }<!>
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, functionDeclarationWithContext,
getter, interfaceDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration, propertyDeclarationWithContext,
propertyWithExtensionReceiver */

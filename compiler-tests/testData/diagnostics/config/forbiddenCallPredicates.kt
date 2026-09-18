// LANGUAGE: +ContextParameters
// KOTRAIL_CONFIG: rules.forbiddenCall=on, rules.forbiddenCall.calls=globalScope=fqn(custom.launch) && receiver(custom.GlobalScope), rules.forbiddenCall.calls=stringLog=fqn(custom.log) && extension(kotlin.String), rules.forbiddenCall.calls=bareRead=fqn(custom.read) && !context(custom.IoScope), rules.forbiddenCall.calls=legacyParse=fqn(custom.parse) && params(kotlin.String, kotlin.Int), rules.forbiddenCall.calls=date=constructor(custom.Date), rules.forbiddenCall.calls=anyPrint=fqn(custom.print*)

package custom

open class CoroutineScope
object GlobalScope : CoroutineScope()
class IoScope
class Date(val millis: Long = 0)

fun CoroutineScope.launch(block: () -> Unit) = block()
fun String.log() = println(this)
fun Int.log() = println(this)
fun read(): String = ""
context(scope: IoScope)
fun read(path: String): String = path
fun parse(text: String, radix: Int): Int = text.toInt(radix)
fun parse(text: String): Int = text.toInt()
fun printAll(vararg items: Any) = items.forEach { println(it) }
fun printed(): Int = 1

fun reported(scope: CoroutineScope) {
    // Reported: the receiver is the GlobalScope object, however the call is spelled.
    <!KOTRAIL_FORBIDDEN_CALL!>GlobalScope.launch { }<!>
    val global = GlobalScope
    <!KOTRAIL_FORBIDDEN_CALL!>global.launch { }<!>

    // Reported: the String overload of the extension, not the Int one.
    <!KOTRAIL_FORBIDDEN_CALL!>"x".log()<!>

    // Reported: read() without an IoScope in context.
    <!KOTRAIL_FORBIDDEN_CALL!>read()<!>

    // Reported: exactly the (String, Int) overload.
    <!KOTRAIL_FORBIDDEN_CALL!>parse("1", 10)<!>

    // Reported: the constructor, whichever arguments.
    <!KOTRAIL_FORBIDDEN_CALL!>Date()<!>
    <!KOTRAIL_FORBIDDEN_CALL!>Date(1L)<!>

    // Reported: a glob on the name.
    <!KOTRAIL_FORBIDDEN_CALL!>printAll(1, 2)<!>
    <!KOTRAIL_FORBIDDEN_CALL!>printed()<!>
}

fun quiet(scope: CoroutineScope, date: Date) {
    // Not reported: launch on a scope that is not GlobalScope.
    scope.launch { }

    // Not reported: the Int extension.
    1.log()

    // Not reported: read with the context parameter provided.
    with(IoScope()) { read("p") }

    // Not reported: the other overload.
    parse("1")

    // Not reported: a member of Date, not its constructor.
    date.millis
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, functionDeclarationWithContext,
functionalType, integerLiteral, lambdaLiteral, localProperty, objectDeclaration, outProjection, primaryConstructor,
propertyDeclaration, stringLiteral, thisExpression, vararg */

// KOTRAIL_CONFIG: rules.forbiddenCall=true, forbiddenCall.functions=kotlin.io.println,java.lang.Thread.sleep,java.util.Date,Logger.debug,Scope.launch
import java.util.Date

object Logger {
    fun debug(message: String) {}
    fun info(message: String) {}
}

object Scope

fun Scope.launch(block: () -> Unit) {}

// A top-level `sleep` is `sleep`, not `java.lang.Thread.sleep`.
fun sleep(millis: Long) {}

fun reported() {
    // Reported: top-level function `kotlin.io.println`.
    <!KOTRAIL_FORBIDDEN_CALL!>println("debug")<!>

    // Reported: Java static method `java.lang.Thread.sleep`.
    <!KOTRAIL_FORBIDDEN_CALL!>Thread.sleep(10)<!>

    // Reported: constructor, listed as the class name `java.util.Date`.
    val now = <!KOTRAIL_FORBIDDEN_CALL!>Date()<!>

    // Reported: member of an object, `Logger.debug`.
    <!KOTRAIL_FORBIDDEN_CALL!>Logger.debug("message")<!>

    // Reported: extension called through an object qualifier, `Scope.launch`.
    <!KOTRAIL_FORBIDDEN_CALL!>Scope.launch { }<!>
}

fun quiet() {
    // Not reported: `kotlin.io.print` is not listed.
    print("debug")

    // Not reported: a different member of the same object.
    Logger.info("message")

    // Not reported: a same-named function from another package.
    sleep(10)

    // Not reported: an extension called on an instance rather than a qualifier is `launch`.
    val scope = Scope
    scope.launch { }

    // Not reported: callable references are not calls.
    val printer: (Any?) -> Unit = ::println
    printer("x")
}

/* GENERATED_FIR_TAGS: callableReference, funWithExtensionReceiver, functionDeclaration, functionalType, javaFunction,
lambdaLiteral, localProperty, nullableType, objectDeclaration, propertyDeclaration, stringLiteral */

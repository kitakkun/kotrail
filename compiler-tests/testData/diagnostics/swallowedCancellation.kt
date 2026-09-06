import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

suspend fun fetch(): String = "data"

fun runLater(block: () -> Unit) {
    block()
}

// Reported: broad catch in a suspend function that only logs.
suspend fun <!PREFER_EXPRESSION_BODY!>logsAndContinues<!>(): String {
    return try {
        fetch()
    } <!SWALLOWED_CANCELLATION!>catch (e: Exception) {
        println(e)
        ""
    }<!>
}

// Reported: Throwable, RuntimeException, and IllegalStateException are also supertypes of CancellationException.
suspend fun catchesThrowable() {
    try {
        fetch()
    } <!SWALLOWED_CANCELLATION!>catch (t: Throwable) {
        println(t)
    }<!>
}

suspend fun catchesRuntime() {
    try {
        fetch()
    } <!SWALLOWED_CANCELLATION!>catch (e: RuntimeException) {
        println(e)
    }<!>
}

suspend fun catchesIllegalState() {
    try {
        fetch()
    } <!SWALLOWED_CANCELLATION!>catch (e: IllegalStateException) {
        println(e)
    }<!>
}

// Reported: only the first clause that receives the cancellation is reported.
suspend fun twoBroadClauses() {
    try {
        fetch()
    } <!SWALLOWED_CANCELLATION!>catch (e: IllegalStateException) {
        println(e)
    }<!> catch (e: Exception) {
        println(e)
    }
}

// Reported: lambda with a suspend function type.
val suspendBlock: suspend () -> Unit = {
    try {
        fetch()
    } <!SWALLOWED_CANCELLATION!>catch (e: Exception) {
        println(e)
    }<!>
}

// Reported: an inline lambda runs in the enclosing suspend function's coroutine.
suspend fun insideInlineLambda(items: List<Int>) {
    items.forEach { item ->
        try {
            fetch()
        } <!SWALLOWED_CANCELLATION!>catch (e: Exception) {
            println("$item ${e.message}")
        }<!>
    }
}

// Not reported: not a suspend context.
fun plainFunction() {
    try {
        println("work")
    } catch (e: Exception) {
        println(e)
    }
}

// Not reported: a non-inline lambda inside a suspend function is not itself suspend.
suspend fun insideNonInlineLambda() {
    runLater {
        try {
            println("work")
        } catch (e: Exception) {
            println(e)
        }
    }
}

// Not reported: the catch type is not a supertype of CancellationException.
suspend fun narrowCatch() {
    try {
        fetch()
    } catch (e: IOException) {
        println(e)
    } catch (e: IllegalArgumentException) {
        println(e)
    }
}

// Not reported: the parameter is rethrown.
suspend fun rethrows() {
    try {
        fetch()
    } catch (e: Exception) {
        println(e)
        throw e
    }
}

// Not reported: cancellation is rethrown conditionally.
suspend fun rethrowsCancellation() {
    try {
        fetch()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        println(e)
    }
}

// Not reported: the exception is translated into another one.
suspend fun wraps() {
    try {
        fetch()
    } catch (e: Exception) {
        throw IllegalArgumentException("wrapped", e)
    }
}

// Not reported: an earlier clause catches CancellationException explicitly.
suspend fun earlierCancellationClause() {
    try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println(e)
    }
}

// Not reported: catching CancellationException itself is deliberate.
suspend fun explicitCancellationCatch() {
    try {
        fetch()
    } catch (e: CancellationException) {
        println(e)
    }
}

// Not reported: the clause re-checks cancellation with ensureActive().
suspend fun ensuresActive() {
    try {
        fetch()
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        println(e)
    }
}

/* GENERATED_FIR_TAGS: functionDeclaration, functionalType, ifExpression, isExpression, lambdaLiteral, localProperty,
nullableType, propertyDeclaration, smartcast, stringLiteral, suspend, tryExpression */

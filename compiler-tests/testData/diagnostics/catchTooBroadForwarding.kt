// KOTRAIL_CONFIG: rules.catchTooBroad=on, rules.catchTooBroad.report=all, rules.noSwallowedCancellation=on
import java.io.IOException

class State<T>(var value: T)
class RenderFailed(cause: Throwable) : Exception(cause)

val failure = State<Throwable?>(null)

fun render() {}
fun report(e: Throwable) {}
fun log(message: String?) {}
fun retry() {}

fun forwarding(onError: (Throwable) -> Unit) {
    // Not reported: the boundary hands the failure whole to the state that records it.
    try {
        render()
    } catch (e: Throwable) {
        failure.value = e
    }

    // Not reported: handed to a callback.
    try {
        render()
    } catch (e: Throwable) {
        onError(e)
    }

    // Not reported: reported, then rethrown.
    try {
        render()
    } catch (e: Throwable) {
        report(e)
        throw e
    }

    // Not reported: rethrown wrapped, the exception passed whole to the constructor.
    try {
        render()
    } catch (e: Exception) {
        throw RenderFailed(e)
    }
}

fun inspecting() {
    // Reported: reading the message is inspecting the exception, not forwarding it.
    try {
        render()
    } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Throwable<!>) {
        log(e.message)
    }

    // Reported: nothing is forwarded.
    try {
        render()
    } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Throwable<!>) {
    }

    // Reported: narrowed by hand.
    try {
        render()
    } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Throwable<!>) {
        if (e is IOException) retry()
    }

    // Reported: a statement that forwards nothing sits beside the forwarding one.
    try {
        render()
    } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Throwable<!>) {
        retry()
        report(e)
    }
}

suspend fun suspending() {
    // Quiet for catchTooBroad, but cancellation is still swallowed: noSwallowedCancellation reports it.
    try {
        render()
    } <!KOTRAIL_SWALLOWED_CANCELLATION!>catch (e: Throwable) {
        failure.value = e
    }<!>
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, functionDeclaration, functionalType, ifExpression, isExpression,
localProperty, nullableType, primaryConstructor, propertyDeclaration, suspend, tryExpression, typeParameter */

// KOTRAIL_CONFIG: rules.catchTooBroad=on
import java.io.IOException

fun save(item: String) {}
fun showError() {}
fun cleanup() {}
fun log(e: Throwable) {}

fun broad(item: String) {
    // Reported: every failure handled alike.
    try {
        save(item)
    } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Exception<!>) {
        showError()
    }

    // Reported: RuntimeException is on the list too.
    try {
        save(item)
    } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: RuntimeException<!>) {
        log(e)
    }
}

fun narrow(item: String) {
    // Not reported: a specific exception.
    try {
        save(item)
    } catch (e: IOException) {
        showError()
    }

    // Not reported: cleanup, then rethrown.
    try {
        save(item)
    } catch (e: Throwable) {
        cleanup()
        throw e
    }

    // Not reported: translated and rethrown.
    try {
        save(item)
    } catch (e: Exception) {
        throw IllegalStateException("save failed", e)
    }

    // Not reported: rethrown on one branch, handled on another.
    try {
        save(item)
    } catch (e: Exception) {
        if (e is IOException) showError() else throw e
    }
}

/* GENERATED_FIR_TAGS: functionDeclaration, ifExpression, isExpression, localProperty, propertyDeclaration, smartcast,
stringLiteral, tryExpression */

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

    // Reported: RuntimeException is on the list too, and a log line is where the failure ends.
    try {
        save(item)
    } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: RuntimeException<!>) {
        println(e)
    }

    // Reported: printStackTrace is a log line too.
    try {
        save(item)
    } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Exception<!>) {
        e.printStackTrace()
    }
}

class Loader {
    var error: String? = null

    // Not reported (report: swallowed): the failure is handed on as a result, as state, or to a function that is not a logger.
    fun load(item: String): Result<Unit> = try {
        save(item)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun loadInto(item: String) {
        try {
            save(item)
        } catch (e: Exception) {
            error = e.message
        }
        try {
            save(item)
        } catch (e: Exception) {
            log(e)
        }
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

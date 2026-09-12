// KOTRAIL_CONFIG: rules.noIgnoredException=true
import java.io.IOException

fun parse(input: String): Int = input.toInt()

fun report(message: String) {
    println(message)
}

// Reported: empty catch body.
fun emptyCatch(input: String) {
    try {
        parse(input)
    } <!KOTRAIL_IGNORED_EXCEPTION!>catch (e: NumberFormatException) {
    }<!>
}

// Reported: the body does something but never looks at the exception.
fun fallbackWithoutLooking(input: String): Int {
    return try {
        parse(input)
    } <!KOTRAIL_IGNORED_EXCEPTION!>catch (e: NumberFormatException) {
        report("bad input")
        0
    }<!>
}

// Reported: each clause is judged on its own.
fun twoClauses(input: String) {
    try {
        parse(input)
    } catch (e: NumberFormatException) {
        report(e.message.orEmpty())
    } <!KOTRAIL_IGNORED_EXCEPTION!>catch (e: IllegalStateException) {
        report("state")
    }<!>
}

// Reported: a shadowing local with the same name is not a use of the parameter.
fun shadowed(input: String) {
    try {
        parse(input)
    } <!KOTRAIL_IGNORED_EXCEPTION!>catch (e: NumberFormatException) {
        val message = "unrelated"
        report(message)
    }<!>
}

// Not reported: the exception is used.
fun logged(input: String) {
    try {
        parse(input)
    } catch (e: NumberFormatException) {
        report(e.message.orEmpty())
    }
}

// Not reported: used through a smart cast / type check.
fun inspected(input: String) {
    try {
        parse(input)
    } catch (e: Exception) {
        if (e is IOException) report("io")
    }
}

// Not reported: rethrown or translated.
fun rethrown(input: String) {
    try {
        parse(input)
    } catch (e: NumberFormatException) {
        throw e
    }
}

fun translated(input: String) {
    try {
        parse(input)
    } catch (e: NumberFormatException) {
        throw IllegalArgumentException("bad input")
    }
}

// Not reported: explicitly ignored names.
fun underscore(input: String) {
    try {
        parse(input)
    } catch (_: NumberFormatException) {
        report("ignored")
    }
}

fun ignoredPrefix(input: String) {
    try {
        parse(input)
    } catch (ignoredFailure: NumberFormatException) {
        report("ignored")
    }
}

// Not reported: used only inside a nested lambda.
fun usedInLambda(input: String) {
    try {
        parse(input)
    } catch (e: NumberFormatException) {
        listOf(1).forEach { report("$it ${e.message}") }
    }
}

/* GENERATED_FIR_TAGS: functionDeclaration, ifExpression, integerLiteral, isExpression, lambdaLiteral, localProperty,
nullableType, propertyDeclaration, stringLiteral, tryExpression, unnamedLocalVariable */

// KOTRAIL_CONFIG: rules.native.objcThrows=on

package custom

import kotlin.native.HiddenFromObjC

class ParseException(message: String) : Exception(message)

class Config(val name: String)

@Throws(ParseException::class)
fun declared(text: String): Config {
    if (text.isEmpty()) throw ParseException("empty")
    return Config(text)
}

fun quiet(text: String): Config = Config(text)

// Reported: a throw, a precondition, a call to a @Throws function, and a call through a function of
// this module that throws, each reaching Swift undeclared.
fun <!KOTRAIL_OBJC_EXPORT_MISSING_THROWS!>throws<!>(text: String): Config {
    if (text.isEmpty()) throw ParseException("empty")
    return Config(text)
}

fun <!KOTRAIL_OBJC_EXPORT_MISSING_THROWS!>requires<!>(text: String): Config {
    require(text.isNotEmpty()) { "empty" }
    return Config(text)
}

fun <!KOTRAIL_OBJC_EXPORT_MISSING_THROWS!>callsDeclared<!>(text: String): Config = declared(text)

fun <!KOTRAIL_OBJC_EXPORT_MISSING_THROWS!>callsThrowing<!>(text: String): Config = throws(text)

fun <!KOTRAIL_OBJC_EXPORT_MISSING_THROWS!>throwsInInlineLambda<!>(texts: List<String>): List<Config> =
    texts.map { text -> check(text.isNotEmpty()); Config(text) }

class Parser {
    fun <!KOTRAIL_OBJC_EXPORT_MISSING_THROWS!>parse<!>(text: String): Config = declared(text)
}

// Not reported: declared, hidden from Objective-C, not public, an override, or catching what it throws.
@Throws(ParseException::class)
fun declaredCaller(text: String): Config = declared(text)

@HiddenFromObjC
fun hidden(text: String): Config = throws(text)

@HiddenFromObjC
class HiddenParser {
    fun parse(text: String): Config = throws(text)
}

internal fun internalCaller(text: String): Config = throws(text)

private fun privateCaller(text: String): Config = throws(text)

interface Reader {
    fun read(text: String): Config
}

class ThrowingReader : Reader {
    override fun read(text: String): Config = throws(text)
}

fun catchesAll(text: String): Config = try {
    throws(text)
} catch (e: Exception) {
    Config("fallback")
}

fun catchesIt(text: String): Config = try {
    declared(text)
} catch (e: ParseException) {
    Config("fallback")
}

// Not reported: an inline function with a reified type parameter has no Objective-C entry point.
inline fun <reified T> reifiedThrows(text: String): T {
    check(text.isNotEmpty())
    throw ParseException(T::class.simpleName ?: text)
}

// Not reported: passing cancellation on from a catch clause is not a new failure.
class CancellationException(message: String) : Exception(message)

fun guarded(block: () -> Config): Config? = try {
    block()
} catch (e: Throwable) {
    if (e is CancellationException) throw e
    null
}

// Not reported: the exception stays behind a lambda that runs later, and a caller of a quiet function.
fun deferred(text: String): () -> Config = { throws(text) }

fun callsQuiet(text: String): Config = quiet(text)

fun main() {
    declared(""); quiet(""); throws(""); requires(""); callsDeclared(""); callsThrowing(""); throwsInInlineLambda(emptyList())
    Parser().parse(""); declaredCaller(""); hidden(""); HiddenParser().parse(""); internalCaller(""); privateCaller("")
    ThrowingReader().read(""); catchesAll(""); catchesIt(""); deferred("")(); callsQuiet(""); guarded { quiet("") }
    reifiedThrows<String>("")
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, functionalType, ifExpression,
interfaceDeclaration, lambdaLiteral, localProperty, override, primaryConstructor, propertyDeclaration, stringLiteral,
tryExpression */

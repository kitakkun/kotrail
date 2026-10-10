// KOTRAIL_CONFIG: rules.noAmbiguousStringName=on
// RENDER_DIAGNOSTICS_FULL_TEXT
import java.io.File
import java.net.URI
import kotlinx.serialization.StringFormat
import kotlinx.serialization.decodeFromString

enum class ValueType { JSON, STRING }

// Not reported: the text is JSON only when the type says so.
fun decode(format: StringFormat, type: ValueType, source: String): Any = when (type) {
    ValueType.JSON -> format.decodeFromString<List<String>>(source)
    ValueType.STRING -> source
}

// Not reported: a local handed the value is decoded in one branch only.
fun decodeCopy(format: StringFormat, type: ValueType, source: String): Any {
    val copy = source
    return if (type == ValueType.JSON) format.decodeFromString<List<String>>(copy) else copy
}

// Not reported: an if whose condition is not about the value.
fun probe(verbose: Boolean, target: String) {
    if (verbose) File(target).exists()
}

// Reported: the condition reads the value itself.
fun probeIfSet(<!KOTRAIL_STRING_NAME_HIDES_KIND!>target<!>: String) {
    if (target.isNotEmpty()) File(target).exists()
}

// Reported: both branches of an if with an else reach a file API.
fun probeEither(flag: Boolean, <!KOTRAIL_STRING_NAME_HIDES_KIND!>target<!>: String) {
    if (flag) File(target).exists() else File(target).delete()
}

// Reported: every branch of a when that covers every case reaches a file API.
fun probeFor(type: ValueType, <!KOTRAIL_STRING_NAME_HIDES_KIND!>target<!>: String): Boolean = when (type) {
    ValueType.JSON -> File(target).exists()
    ValueType.STRING -> File(target).isFile
}

// Reported as a file path only: the URL is reached in one branch, the file outside any.
fun probeTwice(flag: Boolean, <!KOTRAIL_STRING_NAME_HIDES_KIND!>target<!>: String) {
    File(target).exists()
    if (flag) URI(target)
}

// Reported: a name that says only that it holds text is asked for the kind's word alone.
fun parse(format: StringFormat, <!KOTRAIL_STRING_NAME_HIDES_KIND!>text<!>: String): List<String> = format.decodeFromString<List<String>>(text)

fun exists(<!KOTRAIL_STRING_NAME_HIDES_KIND!>value<!>: String) = File(value).exists()

fun load(<!KOTRAIL_STRING_NAME_HIDES_KIND!>s<!>: String) = Class.forName(s)

private const val <!KOTRAIL_STRING_NAME_HIDES_KIND!>RAW<!> = "https://example.com"

fun home() = URI(RAW)

/* GENERATED_FIR_TAGS: const, enumDeclaration, enumEntry, equalityExpression, flexibleType, functionDeclaration,
ifExpression, javaFunction, javaProperty, localProperty, propertyDeclaration, smartcast, starProjection, stringLiteral,
whenExpression, whenWithSubject */

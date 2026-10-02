// KOTRAIL_CONFIG: rules.suppressReason=on

// A file header, set off from the file annotation by a blank line.

<!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@file:Suppress("KOTRAIL_FILE_TOO_LONG")<!>

<!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("KOTRAIL_CATCH_TOO_BROAD")<!>
fun bare() {}

<!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("KOTRAIL_PREFER_VALUE_CLASS")<!>
class Bare(val id: String)

fun local(): Int {
    <!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("KOTRAIL_NARROW_LOCAL_SCOPE")<!>
    val x = 1
    return x
}

@Suppress("KOTRAIL_CATCH_TOO_BROAD") // the SDK throws its own base type; anything else is its bug
fun sameLine() {}

// The SDK throws its own base type; anything else here is a bug in the SDK.
@Suppress("KOTRAIL_CATCH_TOO_BROAD")
fun lineAbove() {}

/**
 * Polls the device; the SDK throws its own base type.
 */
@Suppress("KOTRAIL_CATCH_TOO_BROAD")
fun kdocAbove() {}

/* checked by the schema */ @Suppress("KOTRAIL_CATCH_TOO_BROAD") fun commentBefore() {}

@OptIn(ExperimentalStdlibApi::class)
fun optIn() {}

// A section comment, with a blank line before the annotation.

@Suppress("KOTRAIL_CATCH_TOO_BROAD")
fun blankBetween() {}

// Legacy entry points kept for the old callers until they migrate.
@Suppress("KOTRAIL_CATCH_TOO_BROAD") fun groupA() {}

@Suppress("KOTRAIL_CATCH_TOO_BROAD") fun groupB() {}

@Suppress("KOTRAIL_CATCH_TOO_BROAD") fun groupC() {}

fun plain() {}

<!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("KOTRAIL_CATCH_TOO_BROAD")<!> fun afterPlain() {}

// Each operation below is kept for the old callers until they migrate.

@Suppress("KOTRAIL_CATCH_TOO_BROAD") fun sectionA() {}

@Suppress("KOTRAIL_CATCH_TOO_BROAD") fun sectionB() {}

@Suppress("KOTRAIL_CATCH_TOO_BROAD") fun sectionC() {}

fun plainAgain() {}

// A comment with three blank lines before the annotation.



<!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("KOTRAIL_CATCH_TOO_BROAD")<!>
fun threeBlanksBetween() {}

<!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("UNCHECKED_CAST")<!>
fun compilerIdOwesOneToo(any: Any): List<String> = any as List<String>

@Suppress("unused")
fun exemptUnused() {}

@Suppress("UNUSED_PARAMETER")
fun exemptUnusedParameter(ignored: Int) {}

<!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("unused", "DEPRECATION")<!>
fun exemptMixedIn() {}

<!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("UNCHECKED_CAST", "KOTRAIL_CATCH_TOO_BROAD")<!>
fun mixedIds() {}

class Constructed(<!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("KOTRAIL_CATCH_TOO_BROAD")<!> val kept: Int)

class Accessors {
    <!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@get:Suppress("KOTRAIL_CATCH_TOO_BROAD")<!>
    val targeted: Int = 1

    val explicit: Int
        <!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("KOTRAIL_CATCH_TOO_BROAD")<!> get() = 2
}

@Deprecated("old")
<!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("KOTRAIL_CATCH_TOO_BROAD")<!>
fun stacked() {}

@Deprecated("old")
fun legacy(): Int = 0

class Counter {
    var total = 0
}

// A suppression written on a statement owes a reason as much as one on a declaration.
fun <T> castStatement(raw: Any) {
    <!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("UNCHECKED_CAST")<!>
    (raw as List<T>).forEach { println(it) }
}

fun callStatement() {
    <!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("DEPRECATION")<!> legacy()
}

fun <T> castWithReasonAbove(raw: Any) {
    // The caller hands in the list it built from the same type.
    @Suppress("UNCHECKED_CAST")
    (raw as List<T>).forEach { println(it) }
}

fun callWithReasonOnLine() {
    @Suppress("DEPRECATION") legacy() // kept until the old callers migrate
}

// An assignment, also one desugared into a block around it, is reported once.
fun assignments(counter: Counter): Int {
    var count = 0
    <!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("DEPRECATION")<!>
    count = legacy()
    println(count)
    <!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("DEPRECATION")<!>
    count += legacy()
    println(count)
    <!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("DEPRECATION")<!>
    counter.total += legacy()
    println(count)
    <!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("DEPRECATION")<!> count++
    return count
}

// An annotated block, and an annotated lambda, whose annotations are its anonymous function's.
fun blocks(ready: Boolean) {
    if (ready) <!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("DEPRECATION")<!> {
        legacy()
    }
    println(ready)
    run <!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("DEPRECATION")<!> { legacy() }
}

// A local property's annotation is the property's, not its initializer's: reported once.
fun <T> localProperty(raw: Any): T {
    <!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("UNCHECKED_CAST")<!>
    val first = (raw as List<T>).first()
    return first
}

val initializerExpression: List<String> = <!KOTRAIL_SUPPRESS_WITHOUT_REASON!>@Suppress("UNCHECKED_CAST")<!> (Any() as List<String>)

/* GENERATED_FIR_TAGS: annotationUseSiteTargetFile, annotationUseSiteTargetPropertyGetter, asExpression,
classDeclaration, classReference, functionDeclaration, getter, integerLiteral, localProperty, primaryConstructor,
propertyDeclaration, stringLiteral */

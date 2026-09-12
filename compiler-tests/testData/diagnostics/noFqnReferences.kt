// KOTRAIL_CONFIG: rules.noFqnReferences=true
package app

import java.io.File
import java.sql.Date

// Reported: a class reached through its package; `import java.util.UUID` would work.
val id = <!KOTRAIL_FQN_REFERENCE!>java.util.UUID<!>.randomUUID()

// Reported: a fully qualified constructor call.
val scanner = <!KOTRAIL_FQN_REFERENCE!>java.util<!>.Scanner("x")

// Reported: a fully qualified type in a declaration.
val temp: <!KOTRAIL_FQN_REFERENCE!>java.io.File<!> = File("x")

// Reported: a fully qualified type argument.
val files: List<<!KOTRAIL_FQN_REFERENCE!>java.io.File<!>> = emptyList()

// Reported: a fully qualified nested class; the outer class is what gets imported.
val category: <!KOTRAIL_FQN_REFERENCE!>java.util.Locale.Category?<!> = null

// Reported: a top-level function reached through its package.
fun greet() = <!KOTRAIL_FQN_REFERENCE!>kotlin.io<!>.println("hi")

// Not reported: imported and used by simple name.
val local = File("y")

// Not reported: `Date` is already bound to java.sql.Date by an explicit import, so the other
// Date needs its full name (an alias import is the other option; Kotrail does not insist on it).
val sqlDate = Date(0)
val utilDate = java.util.Date()

// Not reported: `ArrayDeque` already means kotlin.collections.ArrayDeque through the default imports.
val deque = java.util.ArrayDeque<String>()

// Not reported: a same-file class takes the simple name.
class Random
val seeded = java.util.Random(1)

// Not reported: class-qualified access is not a package qualifier.
val maxValue = Int.MAX_VALUE
val entryLike = Map.Entry::class

// Not reported: a callable that would clash with a same-file function.
fun max(a: Int, b: Int): Int = if (a > b) a else b
val bigger = kotlin.math.max(1, 2)

/* GENERATED_FIR_TAGS: classDeclaration, classReference, comparisonExpression, flexibleType, functionDeclaration,
ifExpression, integerLiteral, javaFunction, nullableType, propertyDeclaration, starProjection, stringLiteral */

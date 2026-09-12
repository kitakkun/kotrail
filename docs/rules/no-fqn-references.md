# No FQN references

**Diagnostic:** `KOTRAIL_FQN_REFERENCE` (error, on the qualified name)
**Switch:** `rules.noFqnReferences` (default `true`)
**Severity key:** `severity.noFqnReferences`
**Setting:** `noFqnReferences.allow` (comma-separated package prefixes, default empty)

## What it rejects

```kotlin
val id = java.util.UUID.randomUUID()
val scanner = java.util.Scanner("x")
val temp: java.io.File = File("x")
val files: List<java.io.File> = emptyList()
val category: java.util.Locale.Category? = null
kotlin.io.println("hi")
```

## What it asks for

```kotlin
import java.util.UUID
import java.util.Scanner
import java.io.File
import java.util.Locale

val id = UUID.randomUUID()
val scanner = Scanner("x")
val temp: File = File("x")
val files: List<File> = emptyList()
val category: Locale.Category? = null
println("hi")
```

The message names the import to add and how to write the reference afterwards
(`import java.util.Locale and write Locale.Category`). AI assistants reach for a fully
qualified name when they do not want to touch the import block; the result is a file where the
same class is spelled two ways.

## When it fires

A class, constructor call, callable, or type is written with its package
(`java.util.UUID`, `kotlin.io.println`, `java.io.File`) instead of a simple name. Type
arguments, nested classes, and receivers of calls are all covered.

## When it stays quiet

An import of the simple name would not resolve to the same declaration, because the name is
already bound by:

- an explicit import (or its alias) of something else;
- a star import that provides a declaration with that name;
- the default imports (`kotlin.*`, `kotlin.collections.*`, `java.lang.*` on JVM, ...): e.g.
  `java.util.ArrayDeque` next to `kotlin.collections.ArrayDeque`, or `java.util.Map.Entry`
  next to `kotlin.collections.Map`;
- a declaration in the same package, or in the same file.

An alias import (`import java.util.Date as UtilDate`) would resolve those cases too; Kotrail
does not insist on it, so the fully qualified spelling is accepted there. Packages under a
prefix in `noFqnReferences.allow` are exempt. Class-qualified access such as `Int.MAX_VALUE`
or `Map.Entry::class` is not a package qualifier and never reported.

## Fixtures

`compiler-tests/testData/diagnostics/noFqnReferences.kt`

## Implementation notes

`fir/checkers/NoFqnReferencesChecker.kt` holds three checkers: one on
`FirResolvedQualifier` (a class reached through its package), one on qualified accesses
whose receiver is a package qualifier (callables and constructor calls), and one on
`FirResolvedTypeRef` whose written qualifier contains the package segments. A qualifier is
"written with its package" when its source text starts with `packageFqName`; the compiler's
own `isFullyQualified` flag is not that. The "name is taken" check consults the file's
imports, its declarations, the same package, and `session.defaultImportsProvider`.

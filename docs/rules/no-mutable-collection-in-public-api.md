# No mutable collection in public API

**Diagnostic:** `KOTRAIL_MUTABLE_COLLECTION_IN_PUBLIC_API` (error, on the type reference; on the whole declaration when the type is inferred)
**Key:** `rules.noMutableCollectionInPublicApi` (on by default)
**Settings:** none

## What it rejects

```kotlin
fun tags(): MutableList<String> = mutableListOf()
val cache: HashMap<String, Int> = HashMap()
fun register(names: MutableSet<String>) { ... }
fun inferred() = mutableListOf(1)                 // inferred MutableList<Int>
```

## What it asks for

```kotlin
fun tags(): List<String> = mutableListOf()
val cache: Map<String, Int> = HashMap()
fun register(names: Set<String>) { ... }
fun inferred(): List<Int> = mutableListOf(1)
```

A mutable type in a public signature lets every caller change state the owner did not mean to
share, and pins the implementation to one concrete collection. The read-only interface states
what the API promises; the implementation may still use a mutable collection behind it. The
message names the replacement: `MutableList<String>; expose List<String> instead`.

## When it fires

A function or property whose effective visibility is public or protected (a `protected` member
of a private class does not count), that is not local, not an `override`, and not `expect`, has
a return type or a value-parameter type whose top-level class is one of:

- `kotlin.collections.MutableList`, `MutableSet`, `MutableMap`, `MutableCollection`,
  `MutableIterable`, `MutableIterator`;
- `ArrayList`, `HashMap`, `HashSet`, `LinkedHashMap`, `LinkedHashSet`, whether written through
  the `kotlin.collections` alias or the `java.util` class.

Nullable variants (`MutableMap<K, V>?`) are reported as well. The suggested replacement is
`List`, `Set`, `Map`, `Collection`, `Iterable`, or `Iterator` with the same type arguments.

## When it stays quiet

- The declaration is `private`, `internal`, or local, or is a member of a non-public class.
- The declaration is an `override`: the supertype fixes the signature, so report there instead.
- The mutable type appears only inside type arguments (`List<MutableList<Int>>`).
- Constructors, their parameters, and the properties declared by primary-constructor parameters
  (`class Box(val items: MutableList<Int>)`), property accessors, and backing fields.

## Fixtures

`compiler-tests/testData/diagnostics/mutableCollectionInPublicApi.kt`

## Implementation notes

`fir/checkers/MutableCollectionInPublicApiChecker.kt`. A `FirCallableDeclarationChecker`
restricted to `FirNamedFunction` and `FirProperty`. Visibility uses
`effectiveVisibility.publicApi`. Each type is looked up twice: by the class id of the fully
expanded type (`java.util.ArrayList` on the JVM) and by the class id of the written abbreviation
(`kotlin.collections.ArrayList`), so both spellings match. When the type reference has no real
source (inferred type), the diagnostic falls back to the declaration's source.

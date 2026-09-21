# Prefer value class

**Diagnostic:** `KOTRAIL_PREFER_VALUE_CLASS` (error, on the class name)
**Key:** `rules.preferValueClass` (on by default) (default `error`)
**Settings:** none

## What it rejects

```kotlin
data class UserId(val raw: Long)
data class Email(val address: String) : Comparable<Email> { ... }
```

## What it asks for

```kotlin
@JvmInline
value class UserId(val raw: Long)
```

A data class with one property exists to give a value a type. A value class does the same
while the compiler erases the wrapper wherever it can, and it still generates `equals`,
`hashCode`, and `toString` over the single property. Nothing is lost except the allocation.

## When it fires

A `data class` for which the rewrite to `@JvmInline value class` is legal and preserves
behavior:

- final (data classes always are), not `inner`, not local, not `expect`;
- exactly one primary-constructor parameter, declared as a `val` property, not `vararg`, whose
  type is not `Unit` or `Nothing`;
- no other property that needs a backing field or a delegate (computed properties and
  functions in the body are fine);
- no secondary constructors;
- no supertypes at all;
- no type parameters;
- **no annotations at all**.

## When it stays quiet

- The property is `var`, or there is more than one property.
- The class carries any annotation: `@Serializable`, `@Parcelize`, `@Entity`, and similar
  frameworks typically require a regular data class, so the rule does not guess.
- The class has a supertype, an interface included. A value class is only unboxed while it is
  handled as its own type; held as a supertype it is boxed, and a class that declares a supertype
  is handled as that type by design: a sealed `Action` leaf is passed to `onAction(action)` and
  boxed on the way in, an `Identifier` implementation sits in a `List<Identifier>`. The rewrite
  would save no allocation and cost `copy()` and the freedom to add a property.
- The class is already a value class, is `inner`, is local, or has type parameters.
- The body declares a stored property or a secondary constructor.

## Fixtures

`compiler-tests/testData/diagnostics/preferValueClass.kt`

## Implementation notes

`fir/checkers/PreferValueClassChecker.kt`. A `FirRegularClassChecker` that mirrors the
restrictions the compiler enforces in `FirValueClassDeclarationChecker`: supertypes are checked
with `toRegularClassSymbol(session)?.isInterface`, constructors and properties are read from
`declarations`, the constructor property is identified by `fromPrimaryConstructor`, and other
properties are rejected when `hasBackingField` or `delegate != null`.

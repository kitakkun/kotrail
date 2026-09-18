# Prefer private setter

**Diagnostic:** `KOTRAIL_PREFER_PRIVATE_SETTER` (error, on the exposing property's name)
**Key:** `rules.preferPrivateSetter` (on by default)
**Settings:** none

## What it rejects

```kotlin
private var _count = 0
val count: Int get() = _count
```

## What it asks for

```kotlin
var count: Int = 0
    private set
```

The pair spends two declarations and a naming convention on what one modifier says: the class
writes the value, everyone else reads it. With `private set` there is one property, one name,
and no `_count` to read by mistake from inside the class. It also needs no language feature, so
it is the right rewrite on every Kotlin version; the explicit backing field is for the pairs
that change the exposed type.

## When it fires

Inside one class, a `val` named `foo` whose getter is exactly `get() = _foo`, where `_foo` is a
`var` declared in the same class with strictly narrower visibility, and the two have the same
type. `foo` must be final and not an extension, since those cannot take the rewrite either.

## When it stays quiet

- The exposed type is wider than the var's (`MutableList` behind `List`, `MutableStateFlow`
  behind `StateFlow`): a private setter cannot narrow a type, so
  [prefer explicit backing field](prefer-explicit-backing-field.md) reports that pair instead.
- The getter converts (`_name.uppercase()`) or the value is copied by an initializer.
- The backing property is a `val`.
- Both sides have the same visibility.
- `foo` is `open`, `abstract`, `override`, `expect`, or an extension property.

One pair gets one diagnostic: a pair is either this rule's or the explicit-backing-field rule's,
never both.

## Fixtures

`compiler-tests/testData/diagnostics/preferPrivateSetter.kt`

## Implementation notes

Shares `fir/checkers/PreferExplicitBackingFieldChecker.kt`, which finds the `_foo` / `foo` pairs
once and routes each to the rule whose rewrite fits: a `var` returned as is with equal types
(compared with `AbstractTypeChecker.equalTypes`) comes here, everything else goes to the
explicit backing field.

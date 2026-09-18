# Prefer explicit backing fields

**Diagnostic:** `KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD` (error, on the exposing property's name)
**Switch:** `rules.preferExplicitBackingField` (default `true`)
**Requires:** the explicit backing fields language feature, on by default from Kotlin 2.4.0 and behind `-Xexplicit-backing-fields` on 2.3.x. Where it is off, the rule reports nothing.

## What it rejects

```kotlin
private val _items = MutableStateFlow<List<Item>>(emptyList())
val items: StateFlow<List<Item>> = _items.asStateFlow()
```

## What it asks for

```kotlin
val items: StateFlow<List<Item>>
    field = MutableStateFlow(emptyList())
```

Inside the class, `items` is smart-cast to the field type, so `items.value = ...` keeps working.

## When it fires

A `val` named `foo` declared in a class, whose initializer, delegate, or getter reads a property
named `_foo` declared in the same class with strictly narrower visibility.

## When it stays quiet

- `_foo` is a `var` and `foo` is a getter that returns it unchanged, of the same type: that pair
  needs no backing field, and [prefer private setter](prefer-private-setter.md) reports it instead.

- The exposing property is `var`, `open`, `abstract`, `expect`, or an extension property: an
  explicit backing field is not allowed there.
- Both sides have the same visibility (for example both `private`).
- `foo` does not actually read `_foo`.
- `foo` already has an explicit backing field.
- The pair lives at top level (not covered yet).
- The compilation does not have explicit backing fields enabled: on Kotlin 2.3.x without
  `-Xexplicit-backing-fields` the rewrite would not compile, so nothing is asked for.

## Fixtures

`compiler-tests/testData/diagnostics/preferExplicitBackingField.kt`,
`preferExplicitBackingFieldFeatureOff.kt` (nothing reported with the feature off), and the 2.3.x
overlay of the first, which turns the feature on with a `LANGUAGE` directive

## Implementation notes

`fir/checkers/PreferExplicitBackingFieldChecker.kt`. Looks up `_foo` through
`declaredProperties`, compares visibilities with `Visibilities.compare`, and walks the
initializer/delegate/getter with a `FirVisitorVoid` for a property access resolving to `_foo`.

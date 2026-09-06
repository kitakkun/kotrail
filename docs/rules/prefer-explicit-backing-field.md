# Prefer explicit backing fields

**Diagnostic:** `PREFER_EXPLICIT_BACKING_FIELD` (error, on the exposing property's name)
**Switch:** `rules.preferExplicitBackingField` (default `true`)
**Requires:** Kotlin 2.4, where explicit backing fields are stable.

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

- The exposing property is `var`, `open`, `abstract`, `expect`, or an extension property: an
  explicit backing field is not allowed there.
- Both sides have the same visibility (for example both `private`).
- `foo` does not actually read `_foo`.
- `foo` already has an explicit backing field.
- The pair lives at top level (not covered yet).

## Fixtures

`compiler-tests/testData/diagnostics/preferExplicitBackingField.kt`

## Implementation notes

`fir/checkers/PreferExplicitBackingFieldChecker.kt`. Looks up `_foo` through
`declaredProperties`, compares visibilities with `Visibilities.compare`, and walks the
initializer/delegate/getter with a `FirVisitorVoid` for a property access resolving to `_foo`.

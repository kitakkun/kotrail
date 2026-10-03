# Prefer explicit backing fields

**Diagnostic:** `KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD` (error, on the exposing property's name)
**Key:** `rules.preferExplicitBackingField` (on by default)
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

`foo` has to be a property the compiler lets carry an explicit backing field: final, either by
itself or because nothing can override it any further. That includes an `override val` in a
final class or an object, which the compiler treats as final even without the `final` keyword:

```kotlin
class Host : ViewSource {
    private val _currentView = MutableStateFlow<ViewState?>(null)
    override val currentView: StateFlow<ViewState?> = _currentView.asStateFlow()   // reported
}
```

## When it stays quiet

- `_foo` is a `var` and `foo` is a getter that returns it unchanged, of the same type: that pair
  needs no backing field, and [prefer private setter](prefer-private-setter.md) reports it instead.

- The exposing property is `var`, `open`, `abstract`, `expect`, an extension property, or
  declared in an interface: an explicit backing field is not allowed there.
- The exposing property is an `override val` without `final` in an open, abstract, or sealed
  class, or in an enum class: a subclass (or an enum entry) could override it again, so the
  compiler does not treat it as final.
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
Whether `foo` can carry the field follows the compiler's `FirExplicitBackingFieldForbiddenChecker`:
final by modality, or a member of a final class that is not an enum class.

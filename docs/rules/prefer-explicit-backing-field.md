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

The backing property does not need the `_` prefix. A private property of any name counts when
its use shows it is one:

- `foo`'s initializer or getter is the property itself or one argument-free call on it
  (`state`, `state.asStateFlow()`);
- its type is a subtype of `foo`'s type, as an explicit backing field's type has to be;
- nothing else in the class reads it, except to call on it or assign to it: `state.value = 1`,
  `state.update { }`, `state.emit(x)`, `list.add(x)`, `count = 0` and `count++` are writes.

```kotlin
private val mutableState = MutableStateFlow(0)
val state: StateFlow<Int> = mutableState.asStateFlow()     // reported, backing property 'mutableState'

fun increment() {
    mutableState.update { it + 1 }
}
```

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
- A backing property without the `_` prefix is read elsewhere in the class as a value: returned
  (`fun snapshot() = state`), passed to a call, or stored in another variable. The class hands
  the object out, so it is more than a backing property.
- A backing property without the `_` prefix is not private: code outside the class may read it.
- `foo` derives a value from the property instead of exposing it: `state.map { ... }`,
  `combine(a, b) { ... }`, `items.isEmpty()`. Without the `_` prefix, only the property itself or
  one argument-free call on it whose type fits under `foo`'s counts.
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
Without `_foo`, it takes the property that `foo`'s value reads (as is or through one
argument-free call), checks the type with `AbstractTypeChecker.isSubtypeOf`, and walks the class
for accesses to it outside `foo`: each one has to be an explicit receiver, a safe-call receiver,
or an assignment target (including the desugared target of `x += 1` and `x++`).
Whether `foo` can carry the field follows the compiler's `FirExplicitBackingFieldForbiddenChecker`:
final by modality, or a member of a final class that is not an enum class.

# Retain leak (Compose)

**Diagnostics:** `KOTRAIL_RETAINED_LEAK` (error, on the calculation or the key), `KOTRAIL_RETAINED_REMEMBER_OBSERVER` (error, on the calculation)
**Key:** `rules.compose.retainLeak` (on by default)
**Settings:** `functions` (default `retain`, `RetainedEffect`, `RetainedValuesStoreRegistry.LocalRetainedValuesStoreProvider`), `leakTypes` (default `android.content.Context`, `android.view.View`)

## What it rejects

A value or a key of `retain` that is, or holds, a Context, a View or a class marked
`@DoNotRetain`, and a retained value that `retain` throws for:

```kotlin
class Holder(val view: View)
class Presenter(private val context: Context)

@DoNotRetain
class Session

class Tracker : RememberObserver { /* ... */ }

@Composable
fun Screen(context: Context, view: View) {
    val a = retain { context }                // reported: android.content.Context
    val b = retain { Holder(view) }           // reported: Holder.view (android.view.View)
    val c = retain { Presenter(context) }     // reported: Presenter.context (android.content.Context)
    val d = retain { Session() }              // reported: @DoNotRetain Session
    val e = retain(view) { Cache() }          // reported on the key: android.view.View
    val f = retain { Tracker() }              // reported: a RememberObserver but not a RetainObserver
}
```

## What it asks for

Retain only what does not depend on the host: data, and objects built from data. Anything that
needs the Context or a View takes it where it is used, from the composition, not from the
retained object:

```kotlin
class Presenter(private val repository: Repository) {
    fun title(resources: Resources): String = ...
}

@Composable
fun Screen(id: String, repository: Repository) {
    val presenter = retain(id) { Presenter(repository) }
    Text(presenter.title(LocalContext.current.resources))
}
```

A value that observes its own lifecycle implements `RetainObserver`, or is remembered instead
of retained.

## Why

The Compose runtime documents the constraint on `retain` itself:

> Retained values are held longer than the lifespan of the composable they are associated with.
> [...] Never retain an Android Context or an object that references a Context (including View),
> either directly or indirectly. To mark that a custom class should not be retained (possibly
> because it will cause a memory leak), you can annotate your class definition with
> `androidx.compose.runtime.annotation.DoNotRetain`.

> Keys passed to this composable will be kept in-memory while the computed value is retained for
> comparison against the old keys until the value is retired.

— the KDoc of `retain`, Compose runtime (`androidx.compose.runtime.retain`)

A retained value survives its composable leaving the composition: a destination on the back
stack, collapsed content, an Activity destroyed and recreated for a configuration change. When
that value holds the old Activity, or a View attached to it, the whole destroyed window stays in
memory for as long as the value is retained, and the code that uses it talks to a dead host. The
same KDoc says that retaining a `RememberObserver` that is not also a `RetainObserver` is invalid
and throws.

## When it fires

- The call is one of `functions` (fully qualified; `retain`, `RetainedEffect` and a store
  registry's `LocalRetainedValuesStoreProvider` by default), made in a `@Composable` function or
  getter, or in a lambda of a `@Composable` function type (`setContent { }`, a content slot).
- **Keys** are the arguments of the parameters named `key1`, `key2`, ..., `keys` or `inputs`. The
  **value** is the function-typed argument whose return type is the call's own (`calculation` of
  `retain`); a function whose result is `Unit` (`RetainedEffect`) has keys only.
- `KOTRAIL_RETAINED_LEAK` is reported on the calculation, or on each key, when its type:
  - is one of `leakTypes` or a subclass of one (`AppContext : Context`),
  - is a class annotated `@DoNotRetain`, or a subclass of one,
  - or holds one of those. A project class holds what it stores: every property with a backing
    field or a delegate, private ones included (a private `context` leaks as much as a public
    one), and the stored properties of its project superclasses. A collection, an array, a
    `Pair`, a `Triple` or a `Sequence` holds its elements. A project's own generic class is
    walked with its type arguments in place of its type parameters (`Box(view)` holds a View
    through `Box.value`).
- The message names what is held and how: `android.content.Context` for the value itself,
  `AppContext (android.content.Context)` for a subclass, `Holder.view (android.view.View)` for a
  property, `List<View> (android.view.View)` for elements, `@DoNotRetain Session` for an
  annotated class.
- `KOTRAIL_RETAINED_REMEMBER_OBSERVER` is reported on the calculation when the value's type is a
  `RememberObserver` and not a `RetainObserver`. Keys may be either: the runtime does not dispatch
  to them.

## When it stays quiet

- The function is not `@Composable` and the call is not in a `@Composable` lambda.
- The value and the keys hold only data: `retain { Data(count) }`, `retain(id) { Cache() }`.
- The value is both a `RememberObserver` and a `RetainObserver`.
- A library class other than `leakTypes` ends the walk: what it holds is not visible from its
  type. `retain { WeakReference(context) }` is quiet, as it should be; a `StateFlow<View>` is
  quiet too, which is a miss.
- A property computed by its getter stores nothing: `val view: View get() = ...` is not held.
- What a lambda captures is not in its type: `retain { { view.invalidate() } }` is quiet.
- The value's type is what the call infers: a `retain { context }` whose result is discarded as
  the last statement of a `Unit` lambda returns `Unit`, and nothing is retained.

## Settings

```yaml
rules:
  compose.retainLeak:
    functions:            # replaces the default list
      - androidx.compose.runtime.retain.retain
      - androidx.compose.runtime.retain.RetainedEffect
      - com.example.ui.retainScoped
    leakTypes:            # replaces the default list; @DoNotRetain always counts
      - android.content.Context
      - android.view.View
      - androidx.lifecycle.LifecycleOwner
```

## Related rules

[Remember keys](remember-keys.md) and [Redundant key](redundant-key.md) cover what a key list
says; this rule covers what the keys and the value keep alive. [No callback in model](no-callback-in-model.md)
walks the same type graph for a different finding.

## Fixtures

`compiler-tests/testData/diagnostics/compose/retainLeak.kt`

## Implementation notes

`fir/compose/checkers/ComposableRetainLeakChecker.kt`, a function call checker. The callee is
matched by `callableId` against `functions`; keys by parameter name, the value by comparing the
parameter's function return type with the callee's return type. The walk over what a value holds
resolves supertypes with `lookupSuperTypes` for the leak and `@DoNotRetain` checks, enters only
classes of this module, keeps the properties that have a backing field or are delegated, and
remembers the classes it walked with their type arguments so that a cycle ends. The container
types whose type arguments are walked are shared with `noCallbackInModel` in
`fir/compose/ContainerTypes.kt`.

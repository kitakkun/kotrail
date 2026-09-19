# No unstable parameter (Compose)

**Diagnostic:** `KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER` (error, on the parameter name)
**Key:** `rules.compose.noUnstableParameter` (**experimental, off by default**)
**Setting:** `stableTypes` (default `[]`)

The inference behind this rule is a port of the Compose compiler's and is pinned by fixtures, but
it has not yet been run against large codebases. Switch it on deliberately, compare its findings
with the compiler's own metrics (`-P plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination`),
and report any disagreement:

```yaml
rules:
  compose:
    noUnstableParameter: on
```

## What it rejects

A composable parameter whose type Compose treats as unstable:

```kotlin
@Composable fun UserList(users: List<User>)        // reported: List is an interface
@Composable fun Counter(counter: Counter)          // reported: Counter has a var
@Composable fun Picker(date: java.util.Date)       // reported: a Java class
```

## What it asks for

```kotlin
@Composable fun UserList(users: ImmutableList<User>)
@Composable fun Counter(count: Int, onIncrement: () -> Unit)

@Immutable class User(val id: Long, val name: String)   // stable by inference; @Immutable for the interface case
```

With an unstable argument, Compose cannot skip the composable when a caller passes a value that
is equal but not the same instance, and does not memoize the lambdas that capture the parameter,
so they are allocated again on every recomposition. The fix is one of: an immutable collection
(kotlinx `ImmutableList`, `PersistentMap`, ...), a class whose properties are all `val`s of stable
types, a `@Stable` or `@Immutable` marker on a type whose contract guarantees it, or a
`stableTypes` entry for a type the project cannot annotate.

## How stability is decided

The verdict is the Compose compiler's own inference, reimplemented over the frontend so that it
reads the same as the compiler's reports (the `stable` / `unstable` in the compiler metrics),
rule for rule:

- Primitives, `String`, `Unit`, and function types (composable or not) are stable.
- Enums, objects, and types annotated with `@Stable`, `@Immutable`, or any annotation that
  carries `@StableMarker` are stable, as are their subtypes.
- `Pair`, `Triple`, the kotlinx immutable and persistent collections, Guava's
  immutable collections, `dagger.Lazy`, `BigInteger`, `BigDecimal`, `Locale`, and
  `EmptyCoroutineContext` are stable when their type arguments are, the way the compiler's
  known-stable table says.
- A Java class is unstable. A class from a library that was not compiled with the Compose
  compiler is unstable, `Any` and `java.util.Date` among them.
- An interface is unknown: Compose cannot know what implements it, gives it no stability bits,
  and compares it by instance at runtime. `List`, `Set`, `Map`, and the project's own interfaces
  are reported for this reason unless they carry a stable marker.
- Any other class is as stable as its backing fields and its superclass: unstable at the first
  `var` without a delegate, otherwise combining the stability of every field's type. A `var`
  delegated to `mutableStateOf` is stable (the field holds a `MutableState`), a `val by lazy` is
  not (the field holds a `Lazy`, an interface). Properties without a backing field do not count.
- A value class is as stable as what it wraps, unless it is marked. A star projection and a
  class that refers to itself are unstable.
- A type parameter, or a class whose stability depends on one (`Wrapper<T>`), is left alone: the
  caller decides.

Where the Compose compiler defers a class from another file or module to a runtime check of its
`$stable` field, this rule inlines the answer that field would hold, so the verdict is the same
one the app sees.

## Declaring stable types

`stableTypes` takes the same lines as a Compose
[stability configuration file](https://developer.android.com/develop/ui/compose/performance/stability/fix#configuration-file):
fully qualified names, `*` for one segment, `**` for any number, and an optional mask that says
which type arguments take part in the stability (`*`) and which do not (`_`):

```yaml
rules:
  compose:
    noUnstableParameter:
      stableTypes:
        - com.acme.model.User
        - com.acme.model.*
        - kotlinx.datetime.**
        - com.acme.Box<*,_>
```

Keep the list in step with the compiler's own configuration file: the rule reads the project's
declaration, it does not make Compose skip anything.

## When it stays quiet

- The parameter's stability depends on a type parameter of the function or of its type.
- The function is an `override` (it cannot change its parameter types; the declaring function
  is reported) or `inline` (not a recomposition scope).
- The function is not `@Composable`.

## Fixtures

`compiler-tests/testData/diagnostics/compose/unstableParameter.kt`, one case per rule of the
inference; `compiler-tests/testData/diagnostics/config/unstableParameterStableTypes.kt` for the
patterns and the mask.

## Implementation notes

`fir/compose/checkers/ComposableUnstableParameterChecker.kt`, a `FirNamedFunctionChecker`, runs
`fir/compose/stability/ComposeStability.kt` over each parameter type (the element type of a
vararg). `StabilityInferencer` is a port of the Compose compiler's
`androidx.compose.compiler.plugins.kotlin.analysis.StabilityInferencer` from IR to FIR symbols,
with the same `Stability` algebra (`Certain`, `Unknown`, `Parameter`, `Combined`), the same
known-stable table, and `StableTypeMatcher` as a port of its `FqNameMatcher`. Backing fields of
library classes are known through the metadata flag the deserializer records, and a library class
is recognized as Compose-compiled by its `@StabilityInferred` annotation.

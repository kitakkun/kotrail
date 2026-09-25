# Remember keys (Compose)

**Diagnostic:** `KOTRAIL_EFFECT_KEY_MISSING` (error, on the call)
**Key:** `rules.compose.rememberKeys` (on by default)
**Settings:** `functions` (default `remember`, `rememberSaveable`, `LaunchedEffect`, `DisposableEffect`, `produceState`)

## What it rejects

A `remember` or effect lambda that reads something of the enclosing composable which is not
among the call's keys:

```kotlin
@Composable
fun Price(amount: Long, id: Long) {
    val text = remember { format(amount) }          // reported: amount
    LaunchedEffect(Unit) { load(id) }               // reported: id
}
```

## What it asks for

```kotlin
@Composable
fun Price(amount: Long, id: Long) {
    val text = remember(amount) { format(amount) }
    LaunchedEffect(id) { load(id) }
}
```

or, for a keyless effect that must see the latest value without restarting:

```kotlin
val currentOnClick by rememberUpdatedState(onClick)
LaunchedEffect(Unit) { events.collect { currentOnClick() } }
```

A lambda keyed on nothing runs once and keeps the first value of whatever it captured: the
composable shows the old price, the effect loads the old id, the handler stored in a remembered
holder calls the old callback. Nothing at the call site says so, which is why this survives
review and why generated code does it constantly (`LaunchedEffect(Unit) { viewModel.load(id) }`).

## When it fires

- The call is one of `functions` (by fully qualified name) with a literal lambda as its last
  argument, inside a `@Composable` function.
- The lambda reads, at any depth including nested lambdas, a value of the enclosing composable:
  a parameter, a delegated local (`by remember { mutableStateOf(...) }`, `by flow.collectAsState()`),
  or a local whose initializer reads one of those (transitively).
- No key argument is a direct read of that value. `Unit`, `true` and literals cover nothing.

One finding per call, naming the missing values in source order.

## When it stays quiet

- Every value the lambda reads is among the keys.
- The read is inside `snapshotFlow { }` or `derivedStateOf { }`, which observe on their own.
- The value is a local from `rememberUpdatedState(...)`.
- The value is a local from another `remember*` call (`rememberCoroutineScope()`,
  `rememberNavController()`) or from `CompositionLocal.current`: stable for the composition.
- The lambda is not a literal (a function reference, a variable).
- The call is not inside a composable.

## Fixtures

`compiler-tests/testData/diagnostics/compose/rememberKeys.kt`

## Implementation notes

`fir/compose/checkers/ComposableRememberKeysChecker.kt`, a `FirFunctionCallChecker`. The
enclosing composable's parameters are flagged, then its locals declared before the call (a
delegated one, or one whose initializer reads something flagged) in one pass over the body; a
second visitor collects the flagged symbols the lambda reads, skipping the lambdas of the
observing functions; the keys are compared by symbol after unwrapping smart casts.

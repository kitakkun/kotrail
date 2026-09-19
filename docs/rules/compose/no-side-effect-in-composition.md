# No side effect in composition (Compose)

**Diagnostic:** `KOTRAIL_COMPOSABLE_SIDE_EFFECT_IN_COMPOSITION` (error, on the callee name)
**Key:** `rules.compose.noSideEffectInComposition` (on by default)
**Settings:** `types` (default `[kotlinx.coroutines.Job, kotlinx.coroutines.Deferred]`), `functions` (default `[]`)

## What it rejects

Work started directly in a composable body:

```kotlin
@Composable
fun Screen(scope: CoroutineScope, viewModel: ScreenViewModel) {
    scope.launch { viewModel.load() }          // reported
    viewModel.events.launchIn(scope)           // reported
    val user = scope.async { repository.user() }   // reported
    ...
}
```

## What it asks for

```kotlin
@Composable
fun Screen(viewModel: ScreenViewModel) {
    LaunchedEffect(Unit) { viewModel.load() }
    Button(onClick = { viewModel.refresh() }) { Text("Refresh") }
}
```

A composable body runs whenever Compose decides to recompose it, and that is not a decision the
author makes: a parent's state change, a scroll, a theme switch. Work started in the body is
therefore repeated at times nobody chose, and the coroutine that was launched last time is still
running. An effect (`LaunchedEffect`, `DisposableEffect`, `SideEffect`) runs when its keys change
and is cancelled when the composable leaves; an event handler runs on the event. AI assistants
in particular write `scope.launch` in the body because it is the shortest thing that compiles.

## How a call is recognized

By what the callee declares it returns, or by name:

- `types`: a call to a function whose declared return type is one of these fully qualified types
  starts work. The default covers `launch` (`Job`), `async` (`Deferred`), `Flow.launchIn` (`Job`),
  and a project's own `fun refresh(): Job`. The declared type is what counts, so
  `remember { scope.launch { } }` (declared `T`) and `jobs.first()` are not reported.
- `functions`: fully qualified functions reported by name whatever they return, for a project's
  `viewModel.load()` and the like.

```yaml
rules:
  compose:
    noSideEffectInComposition:
      functions:
        - com.acme.ScreenViewModel.load
        - com.acme.Analytics.track
```

## Where it looks

Only code that runs during composition: the body itself, its `if` / `when` branches and local
`val` initializers, and the lambdas of inline non-composable functions (`forEach`, `let`, `run`,
`apply`), which run in place. Not inspected:

- a lambda handed to a composable: `LaunchedEffect { }`, `remember { }`, `DisposableEffect { }`,
  a content slot;
- a lambda handed to a callback parameter (`onClick = { }`) or stored in a local;
- a local function, an anonymous object.

## When it stays quiet

- The function is not `@Composable`.
- The call returns something other than the listed types and is not listed by name.
- The call sits inside an effect, a callback, or any other lambda that is not inlined.

## Fixtures

`compiler-tests/testData/diagnostics/compose/sideEffectInComposition.kt`,
`compiler-tests/testData/diagnostics/config/sideEffectFunctions.kt`

## Implementation notes

`fir/compose/checkers/ComposableSideEffectChecker.kt`, a `FirNamedFunctionChecker` over
composables. A `FirVisitorVoid` walks the body and stops at every `FirAnonymousFunctionExpression`,
except those bound (through `resolvedArgumentMapping`) to a non-`noinline` parameter of an
`inline`, non-composable callee, whose bodies it enters. A call is reported when its callee's
`callableId` is in `functions` or its `resolvedReturnType`, fully expanded, is in `types`.

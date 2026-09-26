# Remember keys (Compose)

**Diagnostic:** `KOTRAIL_EFFECT_KEY_MISSING` (error, on the call)
**Key:** `rules.compose.rememberKeys` (on by default)
**Settings:** `functions` (default `remember`, `rememberSaveable`, `LaunchedEffect`, `DisposableEffect`, `produceState`)

## What it rejects

A `remember` or effect lambda that captures a value of the enclosing composable which can go
stale and which the call's keys do not cover:

```kotlin
@Composable
fun Price(amount: Long, events: Flow<Event>, onEvent: (Event) -> Unit) {
    val text = remember { format(amount) }                     // reported: amount
    LaunchedEffect(Unit) { events.collect { onEvent(it) } }    // reported: onEvent
}
```

## What it asks for

There are two right fixes, and the message says which one applies to each name.

A data value a `remember { }` computes from belongs in the keys:

```kotlin
val text = remember(amount) { format(amount) }
```

A callback captured by an effect is read through `rememberUpdatedState`: restarting the
collection because a lambda changed identity is itself the classic bug.

```kotlin
val currentOnEvent by rememberUpdatedState(onEvent)
LaunchedEffect(Unit) { events.collect { currentOnEvent(it) } }
```

A data value read by a long-lived effect body (a `collect`, a loop, `onDispose`, a body that
waits for cancellation) goes in the keys if the work should restart when it changes, and
through `rememberUpdatedState` otherwise; the message offers both.

When an effect reads several values, wrapping each one is more ceremony than the effect
deserves. One lambda that does the work captures them all, and only that lambda needs
`rememberUpdatedState`: the lambda is recreated on every recomposition with the current
values, and the effect reads the latest lambda.

```kotlin
val handle by rememberUpdatedState<(Request) -> Unit> { request ->
    when (request) {
        is Request.Home -> onNavigateHome()
        is Request.Plugin -> onClickPlugin(request.id, sessions.firstOrNull { it.id == request.sessionId } ?: selectedSession)
    }
}
LaunchedEffect(channel) { channel.requests.collect { handle(it) } }
```

The message suggests this form whenever two or more values are missing from one effect.

A lambda keyed on nothing runs once and keeps the first value of whatever it captured: the
composable shows the old price, the handler stored in a remembered holder calls the old callback,
the collector calls the first composition's `onEvent` forever. Nothing at the call site says so,
which is why this survives review and why generated code does it constantly.

## When it fires

- The call is one of `functions` (by fully qualified name) with a literal lambda as its last
  argument, inside a `@Composable` function.
- The lambda reads a value of the enclosing composable that can go stale: a parameter, or a
  local whose initializer reads one, transitively.
- No key covers it: a key that reads the same value, or whose text is the property path the
  read roots (`remember(tx.request.url) { parse(tx.request.url) }`). `Unit`, `true` and
  literals cover nothing.
- For `remember` and `rememberSaveable`: any such read. For the effects: a function-typed value
  read in the body after its first suspension point, loop or long-lived lambda (or inside any nested lambda), or a data value read inside a long-lived body (the lambda of
  `collect`, `collectLatest`, `onEach`, `onDispose`, `awaitPointerEventScope`, `withFrameNanos`,
  a `while`/`do`/`for` loop, or a body that reaches `awaitCancellation()`).

One finding per call, naming the missing values in source order with the advice for each:
`onEvent (read it through rememberUpdatedState); tag (add it to the keys if the work should
restart when it changes, otherwise read it through rememberUpdatedState)`.

## When it stays quiet

- Every value the lambda reads is covered by a key, or derived only from covered values.
- The value is a `State`: a delegated local (`by remember { mutableStateOf(...) }`,
  `by flow.collectAsState()`) or a `State`-typed parameter. A `State` is always current when
  read, and writing to one (`visible = true`) is not a read.
- The value is a local from `rememberUpdatedState(...)`, from any other `remember*` call
  (`rememberCoroutineScope()`, a keyed `remember`), from a keyed call under check, or from
  `CompositionLocal.current`: stable for the composition.
- The read is the initial value handed to `mutableStateOf(...)` and its typed variants, or an
  argument of a constructor call inside `remember { }` (`remember { SplitState(initialFraction) }`):
  a seed is meant to be taken once.
- A key spells any property path the read roots, through safe calls and at any depth of the
  lambda: `remember(session?.icon) { session?.icon?.let(::decode) }`,
  `remember(flags.interactiveOnly) { filterBy { flags.interactiveOnly && it.isVisible } }`.
- The value is a callback called at the top level of an effect body before its first suspension
  point, loop, `collect`, `onDispose` or `awaitCancellation()`: it runs once with this
  composition's value. The same callback read after that point, or inside a nested lambda, is
  reported.
- The read is inside `snapshotFlow { }` or `derivedStateOf { }`, which observe on their own.
- The effect is one-shot and reads a data value once (`LaunchedEffect(Unit) { load(id) }`,
  a callback called once with the current value): that is what such effects are for.
- The lambda is not a literal (a function reference, a variable), or the call is not inside a
  composable.

## Fixtures

`compiler-tests/testData/diagnostics/compose/rememberKeys.kt`

## Implementation notes

`fir/compose/checkers/ComposableRememberKeysChecker.kt`, a `FirFunctionCallChecker`. The
enclosing composable's non-`State` parameters are flagged, then its plain locals declared before
the call whose initializer reads something flagged, each with what it depends on; a second
visitor walks the lambda body with an ancestor stack, skipping assignment targets, seed
arguments and observing lambdas, marking reads under long-lived lambdas and loops, and
dropping reads a key covers by symbol, by dependency, or by the text of the property path the
read roots. The advice per name follows from the value's type (function or data), the call
(remember or effect) and whether a read sits in a long-lived body.

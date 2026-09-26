# Remember keys (Compose)

**Diagnostics:** `KOTRAIL_EFFECT_KEY_MISSING` (error, on the call), `KOTRAIL_EFFECT_CAPTURED_BY_CALLEE` (error, on the argument handed to a helper that keeps it)
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

Each value the lambda reads without a key has three answers, and which one is right depends on
what the value is. The message lays out all three; where one is the usual answer for a value (a
callback in an effect, a data value in a `remember { }`), the value carries it.

**The work should run again when the value changes: add it to the keys.** A data value a
`remember { }` computes from belongs here, and so does a data value a long-lived effect body
works with when a new value means new work:

```kotlin
val text = remember(amount) { format(amount) }
LaunchedEffect(userId) { repository.updates(userId).collect { show(it) } }
```

**Only the latest value matters while the work runs: read it through `rememberUpdatedState`.**
A callback captured by an effect belongs here: restarting the collection because a lambda
changed identity is itself the classic bug. Either wrap the value:

```kotlin
val currentOnEvent by rememberUpdatedState(onEvent)
LaunchedEffect(Unit) { events.collect { currentOnEvent(it) } }
```

or wrap what the body does with it. One lambda recreated on every recomposition captures the
current values, and only that lambda needs `rememberUpdatedState`; with several values this is
the same fix written once:

```kotlin
val handle by rememberUpdatedState<(Request) -> Unit> { request ->
    when (request) {
        is Request.Home -> onNavigateHome()
        is Request.Plugin -> onClickPlugin(request.id, sessions.firstOrNull { it.id == request.sessionId } ?: selectedSession)
    }
}
LaunchedEffect(channel) { channel.requests.collect { handle(it) } }
```

`rememberUpdatedState` is the answer for this case only. It is not a way to silence the rule:
wrapping a value that never changes adds code that does nothing.

**The value never changes: hold it where its identity is stable.** A `val` member of the class
the composable belongs to, a `val` of an `object`, or an object made once with `remember { }`
keeps its identity across recompositions, and the rule does not report it (see "When it stays quiet"). When a
parameter only ever receives one object, the fix is to read that object from where it lives
rather than to wrap the parameter:

```kotlin
class DetailScreen(private val navigator: Navigator) {
    @Composable
    fun Content(events: Flow<Event>) {
        LaunchedEffect(Unit) { events.collect { navigator.go(it) } }     // not reported: a member val
    }
}
```

A lambda keyed on nothing runs once and keeps the first value of whatever it captured: the
composable shows the old price, the handler stored in a remembered holder calls the old callback,
the collector calls the first composition's `onEvent` forever. Nothing at the call site says so,
which is why this survives review and why generated code does it constantly.

## Helpers that hide the effect

A project's `ActionEffect(block)` or `ErrorEffect(block)` wraps the effect once for every
screen. Its callers pass lambdas that read their own parameters, and nothing at the call site
looks like an effect:

```kotlin
@Composable
fun ActionEffect(actions: Flow<Action>, block: (Action) -> Unit) {
    LaunchedEffect(Unit) { actions.collect { block(it) } }        // reported here: block
}

@Composable
fun Screen(actions: Flow<Action>, onNavigate: (Route) -> Unit) {
    ActionEffect(actions) { onNavigate(it.route) }                 // reported on the lambda: onNavigate
}
```

The rule summarizes each composable: the parameters its body keeps for the life of an effect
without keeping them current, following calls into other composables, so that a helper that only
hands its `block` on to a helper that keeps it counts too. The summary is written into the class
file as `@InferredEffectCapture(captured = ["block"])` metadata (the `kotrail-annotations`
artifact declares the annotation; the plugin writes it, nobody writes it by hand), and a call
from another module that hands a lambda reading a callback, or a callback parameter itself, for
a captured parameter is reported at the argument as `KOTRAIL_EFFECT_CAPTURED_BY_CALLEE`, naming
the callbacks the lambda reads. The fix is the caller's: keep them current, or hand a lambda
that reads nothing that can go stale.

Inside the module that declares the helper, its callers stay quiet: the helper's own effect is
reported, and that is the one place to fix (or to suppress, when the helper is meant to keep the
first lambda). Only callbacks count at a call site: a lambda that reads a data object of the
caller, a back stack or a channel handed down as a parameter, is not reported, since such objects
keep their identity across recompositions in practice. A helper that reads its lambda through
`rememberUpdatedState` records nothing, and its callers are quiet. A library compiled without
Kotrail carries no metadata and is taken as keeping its lambdas current.

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

One finding per call, naming the missing values in source order, each with its usual answer
where it has one: `onEvent (read it through rememberUpdatedState); tag`. The message then lays
out the three choices above.

## When it stays quiet

- The value is stable, and its identity never changes across recompositions: a `val` member of
  the enclosing class read through `this`, implicitly or explicitly (a `val` constructor parameter
  included), a `val` of an `object`, an `object` or a companion itself, or a local from
  `remember { }`, `rememberSaveable { }` or another call in `functions` (a remembered object,
  a remembered callback). A parameter of the composable is not stable, whatever it holds: the
  caller may pass a different value on the next composition.
- Every value the lambda reads is covered by a key, or derived only from covered values.
- The value is a `State`: a delegated local (`by remember { mutableStateOf(...) }`,
  `by flow.collectAsState()`) or a `State`-typed parameter. A `State` is always current when
  read, and writing to one (`visible = true`) is not a read.
- The value is a local from `rememberUpdatedState(...)`, from any other `remember*` call
  (`rememberCoroutineScope()`, a keyed `remember`), from any call under check, keyed or not, or from
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

`compiler-tests/testData/diagnostics/compose/rememberKeys.kt`, and
`compiler-tests/testData/diagnostics/compose/rememberKeysAcrossModules.kt` for the metadata
between a library and its caller.

## Implementation notes

`fir/compose/checkers/ComposableRememberKeysChecker.kt`, a `FirFunctionCallChecker`, reports a
keyed call from the analysis in `fir/compose/effects/EffectCaptureAnalysis.kt` (flagged values,
coverage by symbol and property path, long-lived bodies, seeds, the one-shot prefix), and a call
into a composable whose captured parameters, from `EffectCaptureService` (a session component:
source bodies, or `@InferredEffectCapture` on the classpath, memoized, transitive), receive a
lambda or a callback. `EffectCaptureService` is an inferred fact on the shared base described in
[Inferred metadata](../../inferred-metadata.md); the shared writer puts `@InferredEffectCapture`
onto non-private composables after Fir2Ir, from the cache the shared warm-up filled.

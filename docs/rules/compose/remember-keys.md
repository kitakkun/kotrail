# Remember keys (Compose)

**Diagnostics:** `KOTRAIL_EFFECT_KEY_MISSING` (error, on the call), `KOTRAIL_EFFECT_CAPTURED_BY_CALLEE` (error, on the argument handed to a helper that keeps it), `KOTRAIL_REDUNDANT_KEY` (error, on the key)
**Key:** `rules.compose.rememberKeys` (on by default)
**Settings:** `functions` (default `remember`, `rememberSaveable`, `LaunchedEffect`, `DisposableEffect`, `produceState`)
**Fix:** automatic for a constant key (`kotrailFix` drops it); none for a missing key

## The principle

A key list is exactly the set of inputs that change. A reader of `remember(a, b) { ... }` or
`LaunchedEffect(a) { ... }` takes each key as "this changes, and the block runs again when it
does", and takes everything else the block reads as fixed for its life. The rule checks both
halves of that reading:

- **Missing keys:** a value the lambda reads that can change, and that no key covers
  (`KOTRAIL_EFFECT_KEY_MISSING`, and `KOTRAIL_EFFECT_CAPTURED_BY_CALLEE` through a helper that
  hides the effect).
- **Constant keys:** a key that never changes while the composable lives
  (`KOTRAIL_REDUNDANT_KEY`).

Both halves read the same `functions`, and `rules.compose.rememberKeys: off` switches both off.
A remembered object sits between them: the missing-key half does not ask for it as a key, since
it keeps its identity across recompositions, and the constant-key half does not reject it as
one, since that identity lasts only as long as its `remember` keeps its keys (see "Why a
remembered object is not a constant key").

## Missing keys

### What it rejects

A `remember` or effect lambda that captures a value of the enclosing composable which can go
stale and which the call's keys do not cover:

```kotlin
@Composable
fun Price(amount: Long, events: Flow<Event>, onEvent: (Event) -> Unit) {
    val text = remember { format(amount) }                     // reported: amount
    LaunchedEffect(Unit) { events.collect { onEvent(it) } }    // reported: onEvent
}
```

### What it asks for

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

A snapshot collection is the exception to the first answer. A `SnapshotStateList`,
`SnapshotStateMap` or `SnapshotStateSet` (a parameter of that type, or a local made by
`mutableStateListOf`, `mutableStateMapOf`, `toMutableStateList` and their kin) keeps its identity
while its contents change, so as a key it never restarts anything, and
[Live value remembered](live-value-remembered.md) reports such a key. When the lambda reads what
the collection holds (as the receiver of a call or a property read, or as an argument of a call),
the value carries "read it inside derivedStateOf or snapshotFlow, or key on a snapshot of it"
instead of "add it to the keys":

```kotlin
val done by remember { derivedStateOf { tasks.count { it.isDone } } }   // follows the contents
val names = remember(tasks.toList()) { tasks.map { it.name } }          // a key that changes with them
```

While [Live value remembered](live-value-remembered.md) is on, a value it reports as read live in
a `remember` (a snapshot collection's contents, a delegated `State`) is its finding alone: this
rule leaves it out of that `remember`'s missing keys and reports the others as before.

A lambda keyed on nothing runs once and keeps the first value of whatever it captured: the
composable shows the old price, the handler stored in a remembered holder calls the old callback,
the collector calls the first composition's `onEvent` forever. Nothing at the call site says so,
which is why this survives review and why generated code does it constantly.

### Helpers that hide the effect

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
hands its `block` on to a helper that keeps it counts too. The summary is recorded for other
modules as an `effectCapture` fact (`captured = ["block"]`): in the module's facts file on the
JVM and Android, as `@InferredEffectCapture` in a klib (the `kotrail-annotations` artifact
declares the annotation; the plugin writes it, nobody writes it by hand), and a call
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

### When it fires

- The call is one of `functions` (by fully qualified name) with a literal lambda as its last
  argument, inside a `@Composable` function.
- The lambda reads a value of the enclosing composable that can go stale: a parameter, or a
  local whose initializer reads one, transitively.
- No key covers it: a key that reads the same value, a key that copies it (`items.toList()`), or
  whose text is the property path the read roots
  (`remember(tx.request.url) { parse(tx.request.url) }`). `Unit`, `true` and literals cover
  nothing.
- For `remember` and `rememberSaveable`: any such read. For the effects: a function-typed value
  read in the body after its first suspension point, loop or long-lived lambda (or inside any nested lambda), or a data value read inside a long-lived body (the lambda of
  `collect`, `collectLatest`, `onEach`, `onDispose`, `awaitPointerEventScope`, `withFrameNanos`,
  a `while`/`do`/`for` loop, or a body that reaches `awaitCancellation()`).

One finding per call, naming the missing values in source order, each with its usual answer
where it has one: `onEvent (read it through rememberUpdatedState); tag`, and a snapshot collection
whose contents the lambda reads with the advice above, never "add it to the keys". The message then
lays out the three choices above.

### When it stays quiet

- The value is stable, and its identity never changes across recompositions: a `val` member of
  the enclosing class read through `this`, implicitly or explicitly (a `val` constructor parameter
  included), a `val` of an `object`, an `object` or a companion itself, or a local from
  `remember { }`, `rememberSaveable { }` or another call in `functions` (a remembered object,
  a remembered callback). A parameter of the composable is not stable, whatever it holds: the
  caller may pass a different value on the next composition.
- Every value the lambda reads is covered by a key, or derived only from covered values. A key
  that copies a value whole covers it: `remember(items.toList()) { items.filter { ... } }` (any
  `to...()` call without arguments), the way to key on the contents of a snapshot collection.
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

## Constant keys

### What it rejects

A key of `remember`, `LaunchedEffect` and their kin that never changes while the composable
lives:

```kotlin
class Screen(private val navigator: Navigator) {
    @Composable
    fun Content(id: String) {
        LaunchedEffect(navigator) { navigator.observe() }             // reported: navigator
        val text = remember(Registry, id) { Registry.lookup(id) }     // reported: Registry
        val page = remember("x", id) { load(id) }                     // reported: "x"
    }
}
```

### What it asks for

A key list that names only what can change, so that "it is a key" reads as "it changes, and the
block runs again when it does":

```kotlin
class Screen(private val navigator: Navigator) {
    @Composable
    fun Content(id: String) {
        LaunchedEffect(Unit) { navigator.observe() }
        val text = remember(id) { Registry.lookup(id) }
        val page = remember(id) { load(id) }
    }
}
```

A value that never changes breaks the reading of a key twice: the reader looks for how it could
change and finds nothing, and the keys that really restart the block no longer stand out. The fix
drops the key (see "Fixes").

When a value is computed from state rather than from a changing input, neither a key nor a
stable holder is the answer: `derivedStateOf { }` reads the state and updates on its own, and a
`remember { derivedStateOf { ... } }` needs no key for the state it observes.

### Why a remembered object is not a constant key

An effect keyed on the object it acts on looks redundant when the object comes from a keyless
`remember { }`, and it is not:

```kotlin
val thumbnails = remember { Thumbnails() }
DisposableEffect(thumbnails) { onDispose(thumbnails::close) }   // quiet
```

The object keeps its identity only as long as its `remember` keeps the keys it has; its
stability is borrowed from that key list. Once the `remember` gains a key, a new object replaces
the old one whenever that key changes: the keyed effect restarts and closes the old object, where
`DisposableEffect(Unit)` would close only the first object, when the composable leaves, and leak
every one after it. Keying the effect on the object couples the two on purpose, so this half
counts only true constants, and a local, however it is made, is never one.

### When it fires

- The call is one of `functions` (by fully qualified name) inside a `@Composable` function.
- The argument is a key: one handed to a parameter named `key1`, `key2`, ..., `keys` or `inputs`,
  as the Compose runtime names them, each element of a `vararg` counted on its own.
- The key is a true constant, one that cannot change while the composable lives:
  - a `val` member of the enclosing class or object read through `this`, implicitly or
    explicitly, a `val` of an `object` or companion, or a top-level `val` (constants included),
    when the `val` is final, not delegated, not an extension and has no custom getter;
  - an `object`, a companion, or an enum entry;
  - a literal other than `true` or `false` (`1`, `"x"`, `null`).
- One finding per key, on the key.

### When it stays quiet

- The key is `Unit`, `true` or `false`: the idiom for "run once".
- The key is a local, however it is made: a remembered object (`remember { Thumbnails() }`, a
  `remember(Registry) { }` keyed only on constants, the result of a helper such as
  `rememberPane()` or `rememberCoroutineScope()`), a `State` from `remember { derivedStateOf { } }`
  or its delegated value, a local computed from a parameter, or a local from a
  `remember(id) { }`. A remembered object stays the same only while its `remember` keeps its
  keys (see above).
- The key can change: a parameter of the composable (the caller may pass a different value on
  the next composition), a receiver of an extension composable, a property read through another
  value (`item.id`, `viewModel.id`), or a call (`list.size`, `id.uppercase()`).
- The key is a `val` member that a getter, a delegate, an override or an extension receiver may
  recompute.
- The argument is not a key: the `initialValue` of `produceState`, the `saver` of
  `rememberSaveable`, the lambda.
- The call is not inside a composable, or its callee is not in `functions`.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `functions` | `remember`, `rememberSaveable`, `LaunchedEffect`, `DisposableEffect`, `produceState` | Fully qualified functions whose trailing lambda is keyed by their other arguments. Both halves check these calls. Replaces the default list. |

## Fixes

- **Constant key:** the key goes with its comma; when every key of a call is constant, the first
  becomes `Unit`, the idiom for "once", and the others go. The fix is left out (the finding
  stays) when a key is named (`key1 = x`), since dropping it would leave the next one misnamed,
  or when a key list is spread from an array.
- **Missing key:** no fix. Which of the three answers is right depends on what the value is, and
  the message lays them out.

`kotrailFix --rules rememberKeys` applies the fixes of this rule alone.

## Related rules

[Live value remembered](live-value-remembered.md) reports a key that stays the same object while
what the lambda reads from it changes in place (a `SnapshotStateList`, a `State`).
[State delegation](state-delegation.md) keeps state reads in the `by` form this rule recognizes
as changing.

## Fixtures

- `compiler-tests/testData/diagnostics/compose/rememberKeys.kt`: missing keys.
- `compiler-tests/testData/diagnostics/compose/rememberKeysSnapshotCollection.kt`, with its
  messages in `rememberKeysSnapshotCollection.diag.txt`: the advice for a snapshot collection.
- `compiler-tests/testData/diagnostics/compose/rememberKeysAcrossModules.kt`: the metadata
  between a library and its caller.
- `compiler-tests/testData/diagnostics/compose/rememberKeysConstant.kt`: constant keys.
- `compiler-tests/testData/diagnostics/compose/liveValueRememberedWithRememberKeys.kt`: a live
  value left to Live value remembered when both rules are on.
- The `kotrailFix` functional test of the Gradle plugin: the removal of a constant key.

## Implementation notes

`fir/compose/checkers/ComposableRememberKeysChecker.kt`, a `FirFunctionCallChecker`, reports a
keyed call from the analysis in `fir/compose/effects/EffectCaptureAnalysis.kt` (flagged values,
coverage by symbol and property path, long-lived bodies, seeds, the one-shot prefix), and a call
into a composable whose captured parameters, from `EffectCaptureService` (a session component:
source bodies, or the `effectCapture` fact of a classpath declaration, memoized, transitive), receive a
lambda or a callback. `EffectCaptureService` is an inferred fact on the shared base described in
[Inferred metadata](../../inferred-metadata.md); the shared writer records the fact of
non-private composables after Fir2Ir, from the cache the shared warm-up filled.

`fir/compose/checkers/ComposableConstantKeyChecker.kt`, a second `FirFunctionCallChecker` under
the same rule, reads the key arguments from `resolvedArgumentMapping` by parameter name and
classifies each one: a `FirLiteralExpression` that is not a Boolean, a `FirResolvedQualifier` of
an `object` (not `Unit`) or a companion, an enum entry, or a final plain `val` that is not local
and whose dispatch receiver is `this` of a class, an object qualifier or absent. It walks nothing
beyond the call's own arguments, so the two checkers share no walk. The fix edits the call's
text through `FixBuilder.over(callSource)`; the edits of the keys of one call never overlap, so
`kotrailFix` applies them all.

# Unwritten state (Compose)

**Diagnostics:** `KOTRAIL_STATE_NEVER_WRITTEN` (error, on the local's name), `KOTRAIL_STATE_NEVER_READ` (error, on the local's name)
**Key:** `rules.compose.unwrittenState` (on by default)

## What it rejects

A local `MutableState` of a composable that only one side ever touches: one whose value nothing
writes, or one whose value nothing reads.

```kotlin
@Composable
fun Screen() {
    val title = remember { mutableStateOf("Home") }        // reported: nothing writes title.value
    val subtitle by remember { mutableStateOf("") }        // reported: a delegated val cannot be assigned
    var draft by remember { mutableStateOf("") }           // reported: nothing reads draft
    Text(title.value)
    Text(subtitle)
    Button(onClick = { draft = "sent" }) { }
}
```

## What it asks for

A value where nothing changes, and no state at all where nothing looks:

```kotlin
@Composable
fun Screen() {
    val title = remember { "Home" }
    Text(title)
    Button(onClick = { send() }) { }
}
```

A `MutableState` nothing writes is a value dressed up as state: `remember { "Home" }` says the
same and gives Compose nothing to observe. A `MutableState` nothing reads is worse than useless:
every write invalidates the scope, so the composable recomposes for a value it never shows. Both
are what an assistant produces when it reaches for `mutableStateOf` by reflex, then wires only
one end of it.

## When it fires

- The function is `@Composable`, and the local is declared in its body (nested lambdas included).
- The local's delegate (`by remember { ... }`, `by rememberSaveable { ... }`) or initializer
  (`= remember { ... }`) has a type that is a subtype of `androidx.compose.runtime.MutableState`.
- `KOTRAIL_STATE_NEVER_WRITTEN`: a plain `val` whose `.value` is never assigned, or a delegated
  `val`, which cannot be assigned at all. Reads do not matter: a state nothing writes never
  changes, read or not.
- `KOTRAIL_STATE_NEVER_READ`: a delegated `var` that is assigned (`x = ...`, `x++`, `x += ...`)
  but never read, or a plain `val` whose `.value` is assigned but never read. Assignments and
  reads count wherever they sit in the function, an event handler or an effect included.

## When it stays quiet

- The state is both read and written somewhere in the function.
- A delegated `var` that nothing assigns: that is a plain never-reassigned `var`, which
  [Prefer val](../prefer-val.md) reports, with its fix that turns the keyword into `val`.
- A plain `val` used as anything but `.value`: passed to another composable (state hoisting),
  returned, used as an effect key, delegated further. Code this function cannot see may read or
  write it there.
- The local's type is a read-only `State`: `derivedStateOf`, `collectAsState`, `produceState`,
  `rememberUpdatedState`. Nothing is expected to write those.
- A remembered plain value (`remember { "x" }`), a property of a class, and any local of a
  function that is not `@Composable`.

## Related rules

[Prefer val](../prefer-val.md) owns the delegated `var` nothing assigns; this rule takes over
where `var` was never the problem. [State delegation](state-delegation.md) asks a plain `val`
used only through `.value` to become a delegated local; both fire on
`val title = remember { mutableStateOf("Home") }` read once, and the delegated form this rule asks
for is the one that reads as a value. [No global mutable state](no-global-mutable-state.md)
covers state Compose cannot observe at all.

## Fixtures

`compiler-tests/testData/diagnostics/compose/unwrittenState.kt`

## Implementation notes

`fir/compose/checkers/ComposableUnwrittenStateChecker.kt`, a property checker on local
properties. The `MutableState` test is a subtype check of the delegate's or initializer's
resolved type against `MutableState<*>`, as the complexity rule tests for `State`. Usages are
collected over the enclosing named function's body with a `FirVisitorVoid` that counts the
state's reads and writes (`x` and `x = ...` for a delegated local, `s.value` and
`s.value = ...` for a plain one; `x++` and `x += 1` count as writes through their desugared
assignment) and flags any other reference to a plain local as an escape. The local's own
declaration is skipped, so that a delegated local's synthesized accessors are not taken for uses.

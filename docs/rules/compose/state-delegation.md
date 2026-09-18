# State delegation (Compose)

**Diagnostic:** `KOTRAIL_PREFER_STATE_DELEGATION` (error, on the local's name)
**Key:** `rules.compose.stateDelegation` (on by default)

## What it rejects

```kotlin
val count = remember { mutableStateOf(0) }
Text("${count.value}")
count.value = count.value + 1
```

## What it asks for

```kotlin
var count by remember { mutableStateOf(0) }
Text("$count")
count++
```

The message names the keyword: `var` for a `MutableState`, `val` for a read-only `State`
(`derivedStateOf`, `collectAsState`, `produceState`, ...).

## When it fires

A local `val` inside a composable (nested lambdas included), whose type is a subtype of
`androidx.compose.runtime.State`, that is not already delegated, and whose every use in the
enclosing function is a `.value` read or write.

## When it stays quiet

The `State` object itself is used: passed to another function (state hoisting), used as an
effect key (`LaunchedEffect(state)`), returned, stored, or used as the receiver of anything but
`.value`. Delegation would change the program there, so `val state = remember { ... }` is the
correct form and is left alone.

## Fixtures

`compiler-tests/testData/diagnostics/compose/stateDelegation.kt`

## Implementation notes

`fir/compose/checkers/PreferStateDelegationChecker.kt`. Subtyping is checked with
`isSubclassOf(State)`; usages are collected over the enclosing named function's body with a
`FirVisitorVoid` that counts `.value` accesses and flags any other reference as an escape.

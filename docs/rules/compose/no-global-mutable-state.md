# No global mutable state (Compose)

**Diagnostics:** `KOTRAIL_GLOBAL_VAR_READ_IN_COMPOSITION` (error, on the read), `KOTRAIL_GLOBAL_VAR_WRITTEN_IN_COMPOSABLE` (error, on the assignment)
**Key:** `rules.compose.noGlobalMutableState` (on by default)
**Settings:** `handlerWrites` (default `false`)

## What it rejects

A composable that reads or assigns a plain global `var`: a top-level one, or a member of an
`object` (a companion object included):

```kotlin
var isDarkTheme = false
object Session { var user: User? = null }

@Composable
fun Header() {
    if (isDarkTheme) DarkLogo() else Logo()        // reported: never recomposes when it changes
    Text(Session.user?.name ?: "guest")            // reported
    Session.user = null                            // reported: assigned during composition
}
```

## What it asks for

State Compose can observe, handed in as a parameter:

```kotlin
class AppState { var isDarkTheme by mutableStateOf(false) }

@Composable
fun Header(isDarkTheme: Boolean, user: User?, onSignOut: () -> Unit) {
    if (isDarkTheme) DarkLogo() else Logo()
    Text(user?.name ?: "guest")
}
```

Compose recomposes a composable when the snapshot state it read changes, and a `var` is not
snapshot state. A composable that reads one shows the value it read first and is never told when
the value changes; the screen goes stale in a way no test of the composable alone reveals. A
composable that assigns one does so on every recomposition, at times nobody chose, and the rest
of the app has no way to see the change. Both are what an assistant writes when it needs a value
"from somewhere" and the nearest thing is a top-level `var` or a singleton.

## When it fires

- The function is `@Composable`.
- A property it reads or assigns is a `var` declared at the top level or in an `object`
  (companion objects included), and is not `lateinit`, delegated, or of a `State` type.
- A read counts when it runs during composition: in the body, in the lambda of an inline
  non-composable function (`let`, `forEach`), in a `remember { }`, or in a content slot (a
  `@Composable` lambda). An assignment counts in those places always, and in an event handler or
  an effect when `handlerWrites` is on.
- One report per variable and kind of use in a function.

## When it stays quiet

- The property is a `val`, a `const`, a `lateinit var` (set once, before any composition), a
  delegated `var` (`by mutableStateOf(...)`, `by Delegates.observable(...)`), or a `var` whose
  type is a `State`.
- The property is a member of a class instance (`holder.value`): that is the parameter's
  stability, covered by [No unstable parameter](no-unstable-parameter.md).
- A read sits in a lambda that runs later: an event handler, `LaunchedEffect`, `DisposableEffect`,
  a callback stored in a local. Such code sees the current value when it runs.
- An assignment in such a lambda, unless `handlerWrites: true`:

  ```yaml
  rules:
    compose.noGlobalMutableState:
      handlerWrites: true    # onClick = { Session.user = null } is reported too
  ```

## Related rules

[No side effect in composition](no-side-effect-in-composition.md) covers work started during
composition; [Remember keys](remember-keys.md) covers a value captured once by an effect. This
rule covers the value that is never observed at all.

## Fixtures

`compiler-tests/testData/diagnostics/compose/globalMutableState.kt`,
`compiler-tests/testData/diagnostics/compose/globalMutableStateHandlers.kt`

## Implementation notes

`fir/compose/checkers/ComposableGlobalMutableStateChecker.kt`, a function checker that walks the
body with a flag saying whether the current lambda runs during composition: inline
non-composable callees, `@Composable` parameter types and `remember*` keep the flag, every other
lambda clears it. A global `var` is a `FirPropertySymbol` that is `isVar`, not local, not
`lateinit`, without a delegate, and whose `callableId` has no class or an `object` class.

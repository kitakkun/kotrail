# Narrow model parameters

**Diagnostic:** `MODEL_PARAMETER_TOO_WIDE` (error, on the parameter name)
**Switch:** `rules.narrowModelParameters` (default `true`)
**Settings:** `narrowModelParameters.maxUnusedProperties` (default `3`),
`narrowModelParameters.scope` (`composables` by default, or `all`)

## What it rejects

```kotlin
@Composable
fun UserCard(user: User) {          // User has ten properties
    Text(user.name)
    AsyncImage(user.avatarUrl)
}
```

## What it asks for

```kotlin
@Composable
fun UserCard(name: String, avatarUrl: String) { ... }
```

or a smaller model that carries exactly what the function reads. Passing a whole model couples
the function to every field of it; in Compose it also widens recomposition and makes previews
and reuse harder.

## When it fires

For each value parameter whose type is a **data class**: count the class's declared properties,
collect the `parameter.property` reads in the body (including lambdas; `state.user.name` counts
as a read of `state.user`), and report when the number of properties never read exceeds
`maxUnusedProperties`.

| Properties | Read | Unread | Default limit 3 |
|---|---|---|---|
| 10 | 2 | 8 | reported |
| 10 | 7 | 3 | allowed |
| 4 | 1 | 3 | allowed |
| 5 | 1 | 4 | reported |

With `scope=composables` only `@Composable` functions are inspected. `scope=all` inspects every
named function.

## When it stays quiet

- The parameter is used as a whole: passed to another function, copied, compared, destructured,
  returned, or used as the receiver of anything but a property read. Narrowing would change the
  program; the callee (if any) is inspected on its own.
- The class is not a data class, or has no more properties than the limit.
- The function is an `override` or `expect` declaration, whose signature is fixed elsewhere.

## Fixtures

- `compiler-tests/testData/diagnostics/compose/narrowModelParameters.kt` (default settings)
- `compiler-tests/testData/diagnostics/config/narrowModelScopeAll.kt` (`scope=all`, limit `1`)

## Implementation notes

`fir/checkers/NarrowModelParametersChecker.kt`. Property reads are detected as a
`FirPropertyAccessExpression` whose explicit receiver resolves to the value parameter; any other
reference to the parameter marks it as used as a whole.

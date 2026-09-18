# Named callback arguments (Compose)

**Diagnostic:** `KOTRAIL_COMPOSABLE_CALLBACK_AS_TRAILING_LAMBDA` (error, on the lambda)
**Key:** `rules.compose.namedCallbackArguments` (on by default)
**Setting:** `allowedPackages` (default `androidx.compose.runtime`)

## What it rejects

Inside a composable, a callback handed to another composable with trailing-lambda syntax:

```kotlin
IconAction { viewModel.retry() }
IconAction(Modifier) { viewModel.retry() }
```

## What it asks for

```kotlin
IconAction(onClick = { viewModel.retry() })
```

The trailing lambda of a composable reads as its content slot. When a library or legacy
composable has a callback as its last parameter (the declaration-side
[no-trailing-callback](no-trailing-callback.md) rule cannot fix APIs you do not own), the call
site names the argument so the reader is not misled.

## When it fires

All of the following hold:

- the call is inside a `@Composable` function (the closest enclosing function, lambdas included);
- the callee is a `@Composable` function outside `allowedPackages`;
- the last argument is a lambda written after the parentheses (or directly after the callee
  name), i.e. a trailing lambda;
- it binds to a parameter whose type is a function type that is not `@Composable`, not
  `suspend`, and returns `Unit`.

## When it stays quiet

- Outside composables; for non-composable callees.
- The parameter is a `@Composable` lambda (a real content slot).
- The lambda is `suspend` (`LaunchedEffect`) or returns a value (`remember`, `derivedStateOf`).
- The callee lives in an allowed package; the Compose runtime effect APIs (`SideEffect`,
  `DisposableEffect`, ...) are allowed by default.
- The lambda is passed inside the parentheses, named or positional.

## Fixtures

`compiler-tests/testData/diagnostics/compose/namedCallbackArguments.kt`

## Implementation notes

`fir/compose/checkers/ComposableNamedCallbackArgumentsChecker.kt`. FIR does not record whether a
lambda was written as a trailing argument, so the checker looks at the call's source text: the
non-blank character before the lambda is `)`, `>`, or an identifier character for a trailing
lambda, and `(`, `,`, or `=` otherwise.

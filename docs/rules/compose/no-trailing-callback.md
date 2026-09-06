# No trailing callback (Compose)

**Diagnostic:** `COMPOSABLE_TRAILING_CALLBACK` (error, on the parameter name)
**Switch:** `rules.compose.noTrailingCallback` (default `true`)
**Severity key:** `severity.compose.noTrailingCallback`

## What it rejects

```kotlin
@Composable
fun ActionCard(title: String, modifier: Modifier = Modifier, onClick: () -> Unit)

ActionCard("Save") { viewModel.save() }   // reads as content, is a click handler
```

The last function-type parameter of a composable is what callers write as a trailing lambda,
and in Compose a trailing lambda is read as the composable's content slot. A callback in that
position misleads everyone reading the call site.

## What it asks for

Put callbacks before the optional parameters, and keep the trailing position for a `@Composable`
content parameter (or nothing):

```kotlin
@Composable
fun ActionCard(title: String, onClick: () -> Unit, modifier: Modifier = Modifier)

@Composable
fun ActionCard(title: String, onClick: () -> Unit, content: @Composable () -> Unit)
```

## When it fires

A `@Composable` named function returning `Unit` whose **last** value parameter has a function type
(`() -> Unit`, `(String) -> Unit`, `suspend () -> Unit`, or a nullable one such as `(() -> Unit)?`)
that is **not** annotated `@Composable`.

## When it stays quiet

- The composable returns a value (`@Composable fun rememberX(...): X`): it is not a UI emitter.
- The function is not `@Composable`.
- The last parameter is a `@Composable` function type, nullable or not.
- The last parameter is not a function type at all (`enabled: Boolean`).
- The function has no value parameters.
- `override` and `expect` functions, whose signature is fixed elsewhere; the declaration they
  implement is reported instead.

## Fixtures

`compiler-tests/testData/diagnostics/compose/trailingCallback.kt`

## Implementation notes

`fir/compose/checkers/ComposableTrailingCallbackChecker.kt`, a `FirSimpleFunctionChecker`.
Composability comes from `isComposable(session)`; the return type is tested with `isUnit`; the
last parameter's `coneType` is tested with `isSomeFunctionType(session)`, which looks at the class
behind the type and therefore accepts nullable function types; `@Composable` on the type is read
from `coneType.customAnnotations`.

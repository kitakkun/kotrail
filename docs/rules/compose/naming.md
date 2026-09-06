# Naming (Compose)

**Diagnostic:** `COMPOSABLE_NAMING` (error, on the function name)
**Switch:** `rules.compose.naming` (default `true`)
**Severity key:** `severity.compose.naming`
**Settings:** none

## What it rejects

```kotlin
@Composable
fun profileCard(user: User) { ... }            // emits UI, named like a function

@Composable
fun RememberFormatter(): Formatter = ...       // returns a value, named like a type
```

## What it asks for

Follow the Compose API guidelines: a composable that emits UI (returns `Unit`) is a noun in
PascalCase, like a type; a composable that returns a value is a verb phrase in camelCase, like
any other function.

```kotlin
@Composable
fun ProfileCard(user: User) { ... }

@Composable
fun rememberFormatter(): Formatter = ...
```

## When it fires

- A `@Composable` named function returns `Unit` (implicitly or with an explicit `: Unit`) and its
  name starts with a lowercase letter. Expected style: `PascalCase`.
- A `@Composable` named function returns anything other than `Unit` and its name starts with an
  uppercase letter. Expected style: `camelCase`.

Only the first character is examined, so `FAB` is a valid UI composable. Local composables are
checked like top-level ones.

## When it stays quiet

- The function is not `@Composable`.
- The function is an `override` or `expect` declaration: its name is fixed by the interface or
  the common declaration (which is checked in its own right).
- The function is an `operator` (`invoke` and friends have language-defined names).
- The name does not start with a letter (`_debugOverlay`, backticked names).

## Fixtures

`compiler-tests/testData/diagnostics/compose/naming.kt`

## Implementation notes

`fir/compose/checkers/ComposableNamingChecker.kt`. A `FirSimpleFunctionChecker` that reads the
resolved return type (`returnTypeRef.coneType.isUnit`) and the first character of the name;
`isComposable(session)` from `fir/compose/ComposeNames.kt` decides whether the function is a
composable. Two arguments are passed to the diagnostic: the name and the expected style.

# No hardcoded string (Compose)

**Diagnostic:** `KOTRAIL_COMPOSABLE_HARDCODED_STRING` (error, on the literal)
**Key:** `rules.compose.noHardcodedString` (**off by default**)
**Setting:** `parameters` (default `[text, label, title, placeholder, contentDescription, message]`)

## What it rejects

A string literal passed to a user-facing parameter of a composable:

```kotlin
Text("Submit")                                  // reported
Button(onClick = onSubmit) { Text("Submit") }   // reported
Card(title = "Welcome")                         // reported: `title` is a listed parameter
```

## What it asks for

```kotlin
Text(stringResource(Res.string.submit))
Card(title = stringResource(Res.string.welcome))
```

Copy that reaches the screen from a literal cannot be translated, and cannot be reviewed by
whoever owns the wording. The rule is off by default because a project that does not localize
has no use for it; switch it on where every user-facing string goes through resources:

```yaml
rules:
  compose.noHardcodedString: on
```

## Which parameters count

Any parameter of a `@Composable` function whose name is in `parameters`, on library and project
composables alike. Setting the key replaces the list:

```yaml
rules:
  compose.noHardcodedString:
    parameters: [text, label, title, placeholder, contentDescription, message, hint]
```

A parameter that carries an identifier rather than copy (`tag`, `key`, `route`, `testTag`) is
simply not listed.

## When it stays quiet

- The argument is not a literal: a resource lookup, a variable, a template (`"$count"`,
  `"${user.name}"`), a concatenation.
- The literal has no letter in it: `"•"`, `" "`, `""`, `"—"`. Punctuation and spacing are layout,
  not copy.
- The call is inside a `@Preview` function or a test function (`test.annotations`): those strings
  never ship.
- The callee is not `@Composable`: `Modifier.testTag("home")`, `Log.d(...)`.

## Fixtures

`compiler-tests/testData/diagnostics/compose/hardcodedString.kt`

## Implementation notes

`fir/compose/checkers/ComposableHardcodedStringChecker.kt`, a `FirFunctionCallChecker`. For a
composable callee it walks `resolvedArgumentMapping`, and for each parameter whose name is listed
checks whether the argument (unwrapped) is a `FirLiteralExpression` of kind `String` with at least
one letter. The preview and test exemptions come from `CheckerContext.containingDeclarations`.

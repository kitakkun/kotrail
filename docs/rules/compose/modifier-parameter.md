# Modifier parameter (Compose)

**Diagnostic:** `KOTRAIL_COMPOSABLE_MODIFIER_PARAMETER` (error, on the function name)
**Switch:** `rules.compose.modifierParameter` (default `true`)
**Severity key:** `severity.compose.modifierParameter`
**Settings:** none

## What it rejects

```kotlin
@Composable
fun Card(mod: Modifier, title: String = "") {   // wrong name, no default, optional before it
    Column(mod) {
        Box(mod) { Text(title) }                 // applied twice
    }
}
```

```
e: Card.kt:8:5 [KOTRAIL_COMPOSABLE_MODIFIER_PARAMETER] [Kotrail] Modifier parameter convention: the Modifier parameter must be named 'modifier'; 'modifier' must default to 'Modifier'; 'modifier' must be the first optional parameter; 'modifier' is passed more than once (apply it to a single root element).
```

## What it asks for

The shape every Compose UI element shares, so that call sites read the same everywhere:

```kotlin
@Composable
fun Card(title: String, modifier: Modifier = Modifier) {
    Column(modifier) {              // once, on the root element
        Text(title)
    }
}
```

## When it fires

The rule applies to `public` or `internal` `@Composable` functions that return `Unit` and have at
least one parameter of type `androidx.compose.ui.Modifier`. Each of the following findings is
checked and all of them are joined into one diagnostic per function:

1. More than one `Modifier` parameter: "only one Modifier parameter is allowed".
2. The parameter is not named `modifier`: "the Modifier parameter must be named 'modifier'".
3. It has no default value, or the default is not the `Modifier` companion (`Modifier` or
   `Modifier.Companion`): "'modifier' must default to 'Modifier'".
4. Some earlier parameter already has a default: "'modifier' must be the first optional
   parameter". Parameters after `modifier` may have defaults.
5. The body reads `modifier` zero times ("'modifier' is never used") or more than once
   ("'modifier' is passed more than once (apply it to a single root element)"). A chain such as
   `modifier.padding(8.dp)` is one read.

## When it stays quiet

- `private` and local composables, which are not part of the module's API.
- Composables without any `Modifier` parameter: the rule does not demand adding one.
- Value-returning composables (`rememberDecorated(base: Modifier): Modifier`).
- `override` and `expect` declarations, whose signature is fixed elsewhere.
- Functions without a body (interface members) skip the use count only; the signature findings
  still apply.

## Fixtures

`compiler-tests/testData/diagnostics/compose/modifierParameter.kt`

## Implementation notes

`fir/compose/checkers/ComposableModifierParameterChecker.kt`. Parameters are matched by
`returnTypeRef.coneType.classId == ComposeNames.MODIFIER`; the default value is accepted when it
is a `FirResolvedQualifier` whose `classId` is `Modifier.Companion`, or `Modifier` with
`resolvedToCompanionObject` set (how `= Modifier` resolves). Uses are counted by a
`FirVisitorVoid` that matches `FirPropertyAccessExpression`s resolving to the parameter symbol,
nested lambdas included.

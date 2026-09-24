# No callback in model (Compose)

**Diagnostic:** `KOTRAIL_CALLBACK_IN_UI_MODEL` (error, on the composable's parameter name)
**Key:** `rules.compose.noCallbackInModel` (on by default)
**Settings:** `allowComposableSlots` (default `false`)

## What it rejects

A UI composable whose parameter type, or a class reachable from it, holds a function-typed
property:

```kotlin
data class UserRow(val name: String, val onClick: () -> Unit)

@Composable
fun UserList(rows: List<UserRow>) { ... }       // reported: rows carries UserRow.onClick

data class Screen(val header: Header)
data class Header(val title: String, val onBack: () -> Unit)

@Composable
fun ScreenContent(screen: Screen) { ... }       // reported: Screen.Header.onBack, through the nested model
```

## What it asks for

```kotlin
data class UserRow(val id: Long, val name: String)

@Composable
fun UserList(rows: List<UserRow>, onClick: (Long) -> Unit) { ... }
```

or one exit for every event, `onAction: (UserAction) -> Unit`, with a sealed `UserAction`.

A lambda that captures anything is a new instance every time it is created, so a model that
holds one is never equal to its previous version: Compose recomposes the composable whenever
the model is rebuilt, whatever it shows. The stability inference does not see the problem, since
function types count as stable, so [no unstable parameter](no-unstable-parameter.md) is quiet.
The model also stops being a plain value: a preview or a screenshot test has to invent callbacks
to build one, and "what to show" is tied to "what to do". State goes down as values; events go
up through the composable's own parameters.

## What counts as a model

A class the project declares that a UI composable takes as a parameter, directly or through
type arguments (`List<UserRow>`, `Map<Id, UserRow>`, `Pair<A, B>`), and every project class
reachable from it through its properties, constructor and body alike, inherited ones included.
Classes from the classpath are not looked into. The composable's own function-typed parameters
are, of course, fine.

## Settings

- `allowComposableSlots`: `true` lets a model carry a `@Composable` function-typed property
  (`trailing: @Composable () -> Unit`). Off by default: a slot belongs to the composable's
  parameters as much as a callback does.

## When it stays quiet

- The composable draws nothing (an effect wrapper), returns a value, is a preview, or is an
  `override` / `expect`.
- No project class reachable from the parameter's type has a function-typed property.
- The property is a `@Composable` slot and `allowComposableSlots` is on.

## Fixtures

`compiler-tests/testData/diagnostics/compose/noCallbackInModel.kt`

## Implementation notes

`fir/compose/checkers/ComposableCallbackInModelChecker.kt`, a `FirNamedFunctionChecker`. For
each parameter of a UI composable (judged as [preview required](preview-required.md) judges it),
the type is expanded and walked: function types are the finding, type arguments are followed,
and a source-declared class or interface contributes its properties and its supertypes, each
class once. The first callback found is reported on the parameter, as `Class.property`.

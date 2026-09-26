# Preview parameter (Compose)

**Diagnostic:** `KOTRAIL_PREVIEW_MODEL_BUILT_INLINE` (error, on the argument)
**Key:** `rules.compose.previewParameter` (on by default)
**Settings:** `minPreviews` (default `2`)

## What it rejects

A `@Preview` function that builds the model it shows by hand, as an argument of a composable:

```kotlin
@Preview
@Composable
private fun UserCardPreview() {
    UserCard(user = User("Ada", plan = Plan.Pro))     // reported: User(...) built inline
}

@Preview
@Composable
private fun OrderListPreview() {
    OrderList(orders = listOf(Order(1, paid = true)))  // reported: Order(...) inside listOf
}
```

## What it asks for

```kotlin
class UserProvider : PreviewParameterProvider<User> {
    override val values = sequenceOf(
        User("Ada", plan = Plan.Pro),
        User("Grace", plan = Plan.Free),
        User("", plan = Plan.Free),
    )
}

@Preview
@Composable
private fun UserCardPreview(@PreviewParameter(UserProvider::class) user: User) {
    UserCard(user = user)
}
```

A model built inline shows one state; the next preview of the same composable builds another
by hand, and the states a component can be in end up scattered across previews and drifting
apart. A `PreviewParameterProvider` lists them in one place, every preview shows all of them,
and screenshot tests iterate the same list.

## When it fires

- The function is a preview (`@Preview`, or a multipreview annotation).
- None of its parameters carries `@PreviewParameter`.
- Some argument of a composable call in its body, at any depth outside lambdas, contains a
  constructor call of a class declared in the project (`User(...)`, `listOf(User(...))`,
  `UiState(items = ...)`). One finding per preview, on the first such argument.
- At least `minPreviews` previews of the same file build that same model class. A single preview
  building a single state is left alone: a provider there is indirection with nothing to
  gather. `minPreviews: 1` reports every one.

## When it stays quiet

- The preview takes a `@PreviewParameter`, whatever else it builds.
- The arguments are strings, numbers, enum entries, `Modifier` chains, lambdas, or values of
  classpath types (`PaddingValues(8.dp)`, `Color(0xFF...)`): only a class of this project is a
  model.
- The model is built inside a lambda passed to the composable (`content = { ... }`), which is
  a slot, not the composable's input.
- The function is not a preview.
- The class is a stand-in made for previews, by the name `Preview*` (`PreviewDataModel`): it is
  not the model the composable shows.
- Fewer than `minPreviews` previews in the file build the model.

## Fixtures

`compiler-tests/testData/diagnostics/compose/previewParameter.kt`

## Implementation notes

`fir/compose/checkers/ComposablePreviewParameterChecker.kt`, a `FirFileChecker`, so that the
previews of a file can be counted per model. For each preview, a visitor over its body stops at
the first composable call with an argument in which a constructor call of a source-declared
class (of kind `CLASS`, not named `Preview*`) occurs outside a lambda.

# Nesting limit (Compose)

**Diagnostic:** `KOTRAIL_COMPOSABLE_NESTING_TOO_DEEP` (error, on the callee name of the first call past the limit)
**Switch:** `rules.compose.nesting` (default `true`)
**Setting:** `compose.nesting.maxDepth` (default `5`; `0` also disables the rule)

## What it rejects

One screen composable that keeps growing into a single deep tree:

```
e: Screen.kt:18:17 [KOTRAIL_COMPOSABLE_NESTING_TOO_DEEP] [Kotrail] Composable calls are nested 6 levels deep here; the limit is 5. Extract this subtree into its own composable.
```

## What it asks for

Extract the inner part into its own named composable. Extraction resets the count, because the
new composable is checked on its own.

## How depth is counted

- A composable call directly in the body has depth 1.
- Depth grows by one for composable calls placed inside a lambda that is bound to a parameter
  whose type is a `@Composable` function type (a content slot). The callee's resolved parameter
  types decide this, so it does not depend on the argument being a trailing lambda.
- Lambdas bound to ordinary parameters add nothing: `LazyColumn { items(n) { Card() } }` counts
  `LazyColumn` as 1 and `Card` as 2, because `LazyColumn`'s `content` is not composable while
  `items`' `itemContent` is. `remember { }` and `onClick = { }` never count.
- Non-composable calls are traversed transparently. Sibling subtrees are independent.

Only the first call whose depth equals `limit + 1` is reported, so one deep tree yields one
error.

## Fixtures

`compiler-tests/testData/diagnostics/compose/nesting.kt` pins: depth 5 accepted, depth 6
reported once, `LazyColumn` content not counted, `remember` not counted, siblings independent,
extraction resets the count. `sample-compose/app/violations/Nesting.kt` shows the error on real
Material 3 code.

## Implementation notes

`fir/compose/checkers/ComposableNestingChecker.kt`. A `FirVisitorVoid` carries the current
depth; when it meets a composable call it looks up, through `resolvedArgumentMapping`, which
lambda arguments are bound to parameters whose cone type carries the `@Composable` annotation,
and descends into those with depth + 1.

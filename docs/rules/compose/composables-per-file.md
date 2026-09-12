# Composables per file (Compose)

**Diagnostic:** `TOO_MANY_COMPOSABLES_IN_FILE` (error, on each composable past the limit)
**Switch:** `rules.compose.composablesPerFile` (default `true`)
**Severity key:** `severity.compose.composablesPerFile`
**Setting:** `compose.composablesPerFile.max` (default `3`; `0` disables)

## What it rejects

A file that declares more non-private UI composables than the limit:

```kotlin
// Screen.kt
@Composable fun HomeScreen(...) { ... }
@Composable fun Header(...) { ... }
@Composable fun ItemRow(...) { ... }
@Composable fun Footer(...) { ... }      // reported
@Composable fun EmptyState(...) { ... }  // reported
```

## What it asks for

One component per file, together with its private helpers and its previews. Components that
are worth exposing are worth their own file; the ones that are not should be `private`.

## How it counts

Counted: `Unit`-returning `@Composable` functions that are not `private`, at top level or as
members of classes and objects. Not counted: private composables, `@Preview` (and multipreview)
functions, value-returning composables, non-composables. Composables beyond the limit are
reported in declaration order, one diagnostic each, with the file's total and the limit.

## Fixtures

`compiler-tests/testData/diagnostics/compose/composablesPerFile.kt`

## Implementation notes

`fir/compose/checkers/ComposablesPerFileChecker.kt`, a `FirFileChecker`, shares its
preview detection with the [preview-required](preview-required.md) rule.

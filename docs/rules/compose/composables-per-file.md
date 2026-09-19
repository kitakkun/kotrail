# Composables per file (Compose)

**Diagnostic:** `KOTRAIL_TOO_MANY_COMPOSABLES_IN_FILE` (error, on each composable past the limit)
**Key:** `rules.compose.composablesPerFile` (on by default)
**Settings:** `max` (default `3`; `0` disables), `countOverloadsSeparately` (default `false`)

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

Overloads of one name are one component: `Button(text = ...)` and `Button(icon = ...)` in the
same file count once, at the position of the first of them, and every overload of a name past
the limit is reported. Set `countOverloadsSeparately: true` to count each declaration instead:

```yaml
rules:
  compose:
    composablesPerFile:
      countOverloadsSeparately: true
```

## Fixtures

`compiler-tests/testData/diagnostics/compose/composablesPerFile.kt`,
`compiler-tests/testData/diagnostics/config/composablesPerFileOverloads.kt`

## Implementation notes

`fir/compose/checkers/ComposablesPerFileChecker.kt`, a `FirFileChecker`, shares its
preview detection with the [preview-required](preview-required.md) rule.

# No redundant else

**Diagnostic:** `REDUNDANT_ELSE_IN_EXHAUSTIVE_WHEN` (error, on the `else` branch)
**Switch:** `rules.noRedundantElse` (default `true`)
**Severity key:** `severity.noRedundantElse` (default `error`)
**Settings:** none

## What it rejects

```kotlin
when (state) {
    is Loading -> showSpinner()
    is Loaded -> showContent(state.items)
    else -> showError()          // unreachable today, silently wrong tomorrow
}

fun code(color: Color): Int = when (color) {
    Color.RED -> 0
    Color.GREEN -> 1
    Color.BLUE -> 2
    else -> -1
}
```

## What it asks for

Delete the `else` branch. The `when` is already exhaustive, so the compiler accepts it as an
expression without `else`, and when a new sealed subclass or enum entry appears the compiler
reports the `when` instead of quietly routing the new case into `else`. The compiler itself
emits the warning `REDUNDANT_ELSE_IN_WHEN`; this rule turns that into an error.

## When it fires

A `when` **with a subject** whose type (after unwrapping nullability and type aliases) is a
sealed class or interface, an enum class, or `Boolean`, whose non-`else` branches already cover
every case, and which nonetheless has an `else` branch. Coverage is the compiler's own
exhaustiveness result: `is` checks and object equality for sealed hierarchies, every entry for
enums, `true`/`false` for booleans, and a `null ->` branch for nullable subjects. Both
expression and statement forms are reported.

## When it stays quiet

- `when` without a subject.
- Subjects of any other type (`Int`, `String`, `Any`, a type parameter, ...), even when the
  compiler considers an `is <SelfType>` branch exhaustive.
- A branch set that is not exhaustive on its own, including a nullable subject without a
  `null ->` branch: the `else` does real work.
- `else` is the only branch.
- An exhaustive `when` that has no `else`.

## Fixtures

`compiler-tests/testData/diagnostics/redundantElse.kt`

## Implementation notes

`fir/checkers/RedundantElseChecker.kt`. Reads `FirWhenExpression.exhaustivenessStatus`, which
the compiler sets to `ExhaustivenessStatus.RedundantlyExhaustive` when an `else` branch is
present and the other branches already cover the subject type. The rule additionally requires
the subject type to be sealed, an enum, or `Boolean` (via `toRegularClassSymbol` /
`isBooleanOrNullableBoolean`) and reports on every branch whose condition is
`FirElseIfTrueCondition`.

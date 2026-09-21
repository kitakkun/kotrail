# Sealed when branch style

**Diagnostic:** `KOTRAIL_SEALED_WHEN_BRANCH_STYLE` (error, on the branch condition)
**Key:** `rules.sealedWhenBranchStyle` (on by default)
**Setting:** `style` (`is` (default) or `object`)

## What it rejects

An object case of a sealed type written in the other form than the rest of the `when`:

```kotlin
when (action) {
    is Save -> save()
    Cancel -> dismiss()          // reported: write `is Cancel ->`
    Retry, Legacy -> again()     // reported twice
}
```

## What it asks for

```kotlin
when (action) {
    is Save -> save()
    is Cancel -> dismiss()
    is Retry, is Legacy -> again()
}
```

A sealed hierarchy mixes classes and objects, and the class cases can only be written with `is`.
Writing the object cases the same way makes every branch of the `when` scan alike: the reader
sees a list of cases, not a list of cases plus a list of values. Both forms behave the same
here: `Cancel ->` compares with `equals`, `is Cancel ->` checks the type, and for a singleton
they select the same branch and both count toward exhaustiveness.

A project that prefers the shorter form flips the setting, and the rule reports `is` checks
against objects instead:

```yaml
rules:
  sealedWhenBranchStyle:
    style: object
```

## When it fires

- The `when` has a subject whose type is a sealed class or sealed interface.
- A branch condition compares the subject with an object (`style: is`), or checks `is` against
  an object (`style: object`). `A, B ->` is judged one alternative at a time.

## When it stays quiet

- The subject is an enum (entries are not types), a Boolean, or anything not sealed.
- The `when` has no subject (`when { action == Cancel -> }`).
- The branch is `else`, has a guard, or is neither an `is` check nor an object comparison
  (`in`, a constant, a function call).

## Fixtures

`compiler-tests/testData/diagnostics/sealedWhenBranchStyle.kt`,
`compiler-tests/testData/diagnostics/config/sealedWhenBranchStyleObject.kt`

## Implementation notes

`fir/checkers/SealedWhenBranchStyleChecker.kt`, a `FirWhenExpressionChecker`. The subject type
comes from `subjectVariable`; a branch condition is split on `FirBinaryLogicExpression` into its
alternatives, and each is an object comparison when it is a `FirEqualityOperatorCall` with `EQ`
whose one `FirResolvedQualifier` resolves to an `object`, or an object check when it is a
`FirTypeOperatorCall` with `IS` whose type is an `object`.

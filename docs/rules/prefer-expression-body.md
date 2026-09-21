# Prefer expression body

**Diagnostic:** `KOTRAIL_PREFER_EXPRESSION_BODY` (error, on the function name)
**Key:** `rules.preferExpressionBody` (on by default)
**Settings:** none
**Fix:** automatic (`kotrailFix` rewrites the block into `= expr`)

## What it rejects

```kotlin
fun total(items: List<Item>): Int {
    return items.sumOf { it.price }
}

fun String.shout(): String {
    return uppercase()
}
```

## What it asks for

```kotlin
fun total(items: List<Item>): Int = items.sumOf { it.price }

fun String.shout(): String = uppercase()
```

A block that only returns one expression is an expression body with extra ceremony. `= expr`
says the same thing in fewer lines and keeps the explicit return type if one is written.

## When it fires

A named function (top-level, member, extension, or local; overrides included) has a block body
whose only statement is a `return` written by the user that targets the function itself and
carries a value.

## When it stays quiet

- The body is already an expression body (`= expr`). FIR desugars an expression body into a
  block with one synthesized `return`; the rule tells the two apart by the source kind of the
  `return`, which is fake (`KtFakeSourceElementKind.ImplicitReturn.FromExpressionBody`) for an
  expression body and real for a written `return`.
- The body has more than one statement, or the `return` is nested inside `if`, `when`, `try`,
  or a lambda.
- The `return` has no value (`fun f() { return }`): its result is a synthesized `Unit`.
- The body is empty, or the declaration has no body (`abstract`, `expect`, interface members).
- Property accessors, constructors, and lambdas are not named functions and are not checked.

## Fixtures

`compiler-tests/testData/diagnostics/preferExpressionBody.kt`

## Implementation notes

`fir/checkers/PreferExpressionBodyChecker.kt`. A `FirSimpleFunctionChecker` that requires the
body's source to be real (a synthesized single-expression block has a fake source), the single
statement to be a `FirReturnExpression` with a real source whose `target.labeledElement` is the
function, and the result's source kind not to be `KtFakeSourceElementKind.ImplicitUnit`.

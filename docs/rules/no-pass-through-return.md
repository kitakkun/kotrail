# No pass-through return

**Diagnostic:** `PASS_THROUGH_RETURN` (error, on the function name)
**Switch:** `rules.noPassThroughReturn` (default `true`)

## What it rejects

```kotlin
fun cache(user: User): User {
    store.add(user)
    return user            // the caller already has `user`
}

fun String.logged(): String {
    log(this)
    return this
}
```

## What it asks for

Return `Unit` when the function is there for its side effect, or return something the function
actually computes. Handing an input back unchanged only invites callers to thread values through
functions that never transform them.

## When it fires

Every `return` of the function (including the implicit one of an expression body) returns the
**same** input unchanged: one particular value parameter, or the extension receiver `this`.
Returns inside nested lambdas belong to the lambda and are ignored.

## When it stays quiet

- Different paths return different inputs (`if (first) a else b`): the function selects.
- Any path returns something else (`x ?: default`, `s.trim()`, `user.copy(...)`).
- The function is an `override`, an `operator`, `expect`, or `inline` (DSL helpers such as
  `T.also`-style functions are legitimately shaped this way).
- The function returns `Unit` or never returns normally.

## Fixtures

`compiler-tests/testData/diagnostics/passThroughReturn.kt`

## Implementation notes

`fir/checkers/PassThroughReturnChecker.kt`. Collects `FirReturnExpression`s whose target is the
function itself, unwraps smart casts, and maps each result to a parameter name or `this`; reports
when the set of returned inputs has exactly one element.

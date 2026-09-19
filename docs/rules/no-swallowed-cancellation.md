# No swallowed cancellation

**Diagnostic:** `KOTRAIL_SWALLOWED_CANCELLATION` (error, on the catch clause)
**Key:** `rules.noSwallowedCancellation` (on by default)
**Settings:** none

## What it rejects

```kotlin
suspend fun load(): Result {
    return try {
        api.fetch()
    } catch (e: Exception) {   // CancellationException is an Exception too
        log(e)
        Result.Empty
    }
}
```

## What it asks for

Let cancellation propagate. Either rethrow it before handling the rest:

```kotlin
} catch (e: Exception) {
    if (e is CancellationException) throw e
    log(e)
    Result.Empty
}
```

or catch only the failures the code can actually handle (`IOException`, `HttpException`, a
domain error type). Coroutine cancellation is delivered as a `CancellationException`; a broad
catch that swallows it keeps the coroutine running after its scope was cancelled, leaking work,
resources, and stale UI updates.

## When it fires

All of the following hold:

- The `try` is in a suspend context: the closest enclosing function is a `suspend` function or
  a lambda whose type is a suspend function type (`suspend () -> T`, as passed to `launch`,
  `withContext`, `flow { }`, ...). Inline lambdas (`forEach`, `let`, `run`, ...) inherit the
  context of the function around them.
- A catch clause has a parameter type that would receive a `CancellationException`:
  `Throwable`, `Exception`, `RuntimeException`, or `IllegalStateException` (Kotlin or
  `java.lang` spelling).
- That clause contains no `throw` and no `ensureActive()` call.

Only the **first** clause that would receive the cancellation is examined and reported: later
clauses never see the exception, so a single `try` yields a single diagnostic.

## When it stays quiet

- The `try` is in an ordinary (non-suspend) function, or in a non-inline lambda with an ordinary
  function type inside a suspend function.
- The clause catches `CancellationException` itself (`kotlinx.coroutines`,
  `kotlin.coroutines.cancellation`, or `java.util.concurrent`): catching it explicitly is a
  deliberate choice.
- An earlier clause of the same `try` catches `CancellationException`, so the broad clause never
  receives it.
- The clause contains any `throw` (`throw e`, `if (e is CancellationException) throw e`,
  `throw DomainError(e)`), or calls `kotlinx.coroutines.ensureActive` on a scope, job, or
  context. The rule does not prove that the throw is reached; any `throw` is taken as intent to
  propagate.
- The catch type is not a supertype of `CancellationException` (`IOException`,
  `IllegalArgumentException`, custom exceptions).

## Fixtures

`compiler-tests/testData/diagnostics/swallowedCancellation.kt`

## Implementation notes

`fir/checkers/SwallowedCancellationChecker.kt`, a `FirTryExpressionChecker`. The suspend
context is derived from `CheckerContext.containingDeclarations`: walking outward, an anonymous
function counts when its status is `suspend` or its type `isSuspendOrKSuspendFunctionType`; an
inline lambda (`InlineStatus.Inline`) is transparent; any other function ends the search with
its own `isSuspend`. The catch type is fully expanded and compared by `ClassId` against the fixed
supertype chain of `CancellationException`, which makes the rule independent of whether
`kotlinx.coroutines` is on the classpath. `CancellationException` is matched in all three of its
spellings: `kotlinx.coroutines.CancellationException` is a typealias for
`kotlin.coroutines.cancellation.CancellationException`, which is the class itself on non-JVM
targets and expands to `java.util.concurrent.CancellationException` on the JVM. The rethrow check is a `FirVisitorVoid` over the clause
body that stops at the first `FirThrowExpression` or a call whose resolved callee is
`kotlinx.coroutines.ensureActive`. The `ensureActive` path is not covered by the fixture because
the test classpath has no `kotlinx.coroutines`.

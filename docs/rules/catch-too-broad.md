# Catch too broad

**Diagnostic:** `KOTRAIL_CATCH_TOO_BROAD` (error, on the catch parameter)
**Key:** `rules.catchTooBroad` (on by default)
**Settings:** `types` (default `[kotlin.Throwable, kotlin.Exception, kotlin.RuntimeException, java.lang.Error]`), `report` (`swallowed`, the default, or `all`), `loggers` (functions that only log)

## What it rejects

A catch clause that names one of the root exception types and handles what it caught:

```kotlin
try {
    repository.save(item)
} catch (e: Exception) {          // reported
    showError()
}
```

## What it asks for

The exceptions the code recovers from, named; the rest left to propagate:

```kotlin
try {
    repository.save(item)
} catch (e: IOException) {
    showError()
}
```

`catch (e: Exception)` treats every failure alike: the network error the clause was written
for, the `NullPointerException` that is a bug and should surface, the `CancellationException`
a coroutine needs to see. Written once as a reflex, it hides the next bug for good. A clause
that rethrows is not handling but cleaning up or translating, and is left alone.

Not every broad clause is a reflex. A boundary that turns any failure into a result or an error
state (`Result.failure(e)`, `state = Error(e.message)`, `onError(e)`) hands the failure on, and
whoever receives it decides. What hides bugs is the clause where the failure goes no further: the
exception is never read, or it is only logged. By default (`report: swallowed`) the rule reports
those clauses alone; `report: all` reports every broad clause, for a codebase that wants the
boundaries named explicitly through `exclude`.

```kotlin
try {
    repository.save(item)
} catch (e: Exception) {
    logger.warn("save failed", e)   // reported under `swallowed`: the failure ends in a log line
}

try {
    repository.save(item)
} catch (e: Exception) {
    onError(e)                      // quiet under `swallowed`: handed on
}
```

`loggers` lists the functions that only log (globs over fully qualified names; `println`,
`printStackTrace`, `android.util.Log.*`, SLF4J, kotlin-logging, Kermit, Timber and the IntelliJ
`Logger` by default). Handing the exception to one of them alone counts as swallowing it.

## When it fires

- A catch parameter's type is exactly one of `types` (subtypes are not matched: catching
  `IllegalStateException` is a decision).
- The clause neither throws its parameter anywhere nor ends in a `throw`.
- Under `report: swallowed`, the clause never reads its parameter, or reads it only as an
  argument of a function in `loggers`.

## When it stays quiet

- The caught type is specific.
- The clause rethrows: `throw e` anywhere in it, or a `throw` of anything as its last
  statement (a wrapped rethrow).
- Under `report: swallowed`, the parameter reaches anything but a logger: a return value, a
  property, a callback, a constructor, a string template.
- The catch parameter carries the suppression: `catch (@Suppress("KOTRAIL_CATCH_TOO_BROAD") e: Exception)`
  names exactly one clause, where `@Suppress` on the enclosing function or `try` would cover every
  clause in it.
- The location matches the rule's `exclude` predicate. A project's error boundaries are
  declared there, not by weakening the rule:

  ```yaml
  rules:
    catchTooBroad:
      exclude: annotated(com.acme.ErrorBoundary) || name(*Boundary) || class(*PluginHost)
  ```

## Related rules

[No swallowed cancellation](no-swallowed-cancellation.md) covers the one exception a broad
catch must always let through in coroutines; [No ignored exception](no-ignored-exception.md)
covers a clause that does nothing at all. This rule covers the shape both of them start from.

## Fixtures

`compiler-tests/testData/diagnostics/catchTooBroad.kt`

## Implementation notes

`fir/checkers/CatchTooBroadChecker.kt`, a `FirTryExpressionChecker`. The rethrow test is a
visitor over the clause for a `throw` of the parameter, plus a look at the last statement. The
swallowed test is a visitor over the clause's reads of the parameter, each checked against the
innermost enclosing call: only reads that are arguments of a `loggers` function are allowed.

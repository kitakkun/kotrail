# Catch too broad

**Diagnostic:** `KOTRAIL_CATCH_TOO_BROAD` (error, on the catch parameter)
**Key:** `rules.catchTooBroad` (on by default)
**Settings:** `types` (default `[kotlin.Throwable, kotlin.Exception, kotlin.RuntimeException, java.lang.Error]`)

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

## When it fires

- A catch parameter's type is exactly one of `types` (subtypes are not matched: catching
  `IllegalStateException` is a decision).
- The clause neither throws its parameter anywhere nor ends in a `throw`.

## When it stays quiet

- The caught type is specific.
- The clause rethrows: `throw e` anywhere in it, or a `throw` of anything as its last
  statement (a wrapped rethrow).
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
visitor over the clause for a `throw` of the parameter, plus a look at the last statement.

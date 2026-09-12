# No ignored exception

**Diagnostic:** `KOTRAIL_IGNORED_EXCEPTION` (error, on the catch clause; argument: the parameter name)
**Switch:** `rules.noIgnoredException` (default `true`)
**Severity key:** `severity.noIgnoredException`
**Settings:** none

## What it rejects

```kotlin
val port = try {
    input.toInt()
} catch (e: NumberFormatException) {   // `e` is never looked at
    DEFAULT_PORT
}

try {
    file.delete()
} catch (e: IOException) {
}
```

## What it asks for

Do something with the exception, or say out loud that you are dropping it:

```kotlin
} catch (e: NumberFormatException) {
    logger.warn("Invalid port '$input', using $DEFAULT_PORT", e)
    DEFAULT_PORT
}

} catch (_: IOException) {          // declared as ignored
}

} catch (e: IOException) {
    throw StorageError("could not delete $file", e)   // translated
}
```

An exception that is caught and neither handled, logged, wrapped, nor rethrown hides failures
from everyone who later has to debug the program.

## When it fires

A catch clause whose parameter is never referenced anywhere in the clause body (including
nested lambdas and local declarations), and whose body contains no `throw`. Each clause of a
`try` is judged on its own, so one `try` can produce several diagnostics. The clause may also
be reported by [No swallowed cancellation](no-swallowed-cancellation.md); the two rules are
independent.

## When it stays quiet

- The parameter is named `_` or its name starts with `ignored` (`ignored`, `ignoredFailure`).
- The parameter is used in any way: logged, inspected (`e is IOException`), passed along,
  read (`e.message`), or rethrown (`throw e`).
- The body contains a `throw` of anything: the exception is being translated into another one.

## Fixtures

`compiler-tests/testData/diagnostics/ignoredException.kt`

## Implementation notes

`fir/checkers/IgnoredExceptionChecker.kt`, a `FirTryExpressionChecker`. For each `FirCatch`
whose parameter name is not `SpecialNames.UNDERSCORE_FOR_UNUSED_VAR` (what the raw FIR builder
assigns to `_` in a catch) and does not start with `ignored`, a `FirVisitorVoid` walks the
clause block looking for a `FirResolvedNamedReference` whose `resolvedSymbol` is the
parameter's symbol, or for any `FirThrowExpression`; neither found means the clause is reported
with the parameter's name as the argument.

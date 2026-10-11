# Catch too broad

**Diagnostic:** `KOTRAIL_CATCH_TOO_BROAD` (error, on the catch parameter)
**Key:** `rules.catchTooBroad` (on by default)
**Settings:** `types` (default `[kotlin.Throwable, kotlin.Exception, kotlin.RuntimeException, java.lang.Error]`), `report` (`swallowed`, the default, or `all`), `loggers` (functions that only log); failure boundaries come from the [`unguardedBoundary`](unguarded-boundary.md) settings

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
exception is never read, or it is only logged, away from a [failure boundary](#failure-boundaries).
By default (`report: swallowed`) the rule reports those clauses alone; `report: all` reports
every broad clause outside the failure boundaries, for a codebase that wants its other
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
`Logger` by default). Handing the exception to one of them alone counts as swallowing it, except
at a failure boundary.

## Failure boundaries

Narrow inside, broad at boundaries. In the middle of the logic a broad catch hides bugs: the
caller would have seen the failure, and now nobody does. At the top of a unit of work there is
no caller: an exception that gets past the last catch ends a launched coroutine, stops the loop
that consumes a channel, or kills the thread, and the failure goes nowhere useful. There a narrow
catch is the risk, because any failure it does not name ends the work and only the default
handler sees it, and a broad catch that logs or reports the exception is the right shape:

```kotlin
val requests = Channel<Request>(UNLIMITED).also {
    scope.launch {
        for (request in it) {
            try {
                client.send(request)
            } catch (e: IOException) {
                log.warn("send failed", e)
            } catch (e: Exception) {
                log.error("request failed", e)   // quiet: the boundary logs what nothing else names
            }
        }
    }
}
```

At a failure boundary a broad clause is quiet when it reads its parameter at all: it logs it,
reports it, or hands it on. One that never reads it is still reported, under either `report`
setting, because the failure vanishes there.

Boundaries are defined by the [unguardedBoundary](unguarded-boundary.md#failure-boundaries)
settings, which this rule reads whether or not that rule is on. In short, a `try` is at a failure
boundary when it is a statement at the top of a root body (inside an `if`, a `when`, a loop or
another `try` there too), seen through the lambdas of inline functions such as `apply`, `let`,
`use` and `forEach`. A root body is the lambda of a root launcher (`launch`, `LaunchedEffect`,
`thread`, `Executor.execute` and the rest of `unguardedBoundary.functions`) or of a flow chain
that ends in `launchIn`, `stateIn` or `shareIn`; a lambda that returns `Unit`, converted to an
interface declared outside the module (`view.setOnClickListener { }`, `Runnable { }`); the body
of a function that overrides a member declared outside the module and returning `Unit` (a
library listener, `Runnable.run`), the members of `Any` aside; and a function the project
declares a boundary through
`unguardedBoundary.annotations` or `unguardedBoundary.names`. `async`, `withContext`,
`coroutineScope`, `runBlocking` and a `flow { }` collected elsewhere are not roots, and
`runCatching` ends the search. Neither is an override, or a converted lambda, that returns a
value (`ClassLoader.loadClass`, a command handler that returns its result) or is suspend: its
caller consumes the result and receives the failure with it, or the coroutine that called it
does, so a broad catch there that only logs is reported as anywhere else.

```yaml
rules:
  unguardedBoundary:
    annotations: [com.acme.FailureBoundary]     # also read by catchTooBroad
```

```kotlin
class Device(scope: CoroutineScope) {
    init {
        scope.launch {
            try { open() } catch (e: Exception) { log.warn("open failed", e) }   // quiet: a boundary
        }
        try { open() } catch (e: Exception) { log.warn("open failed", e) }       // reported: init is not
    }
}

fun start() = device.apply {
    try { open() } catch (e: Exception) { log.warn("open failed", e) }           // reported: start is not a root
}

override fun onClick(v: View) {
    state.apply {
        try { submit() } catch (e: Exception) { log.warn("submit failed", e) }   // quiet: a library listener
    }
}
```

A broad catch at a boundary still swallows `CancellationException` when the root is a coroutine.
[No swallowed cancellation](no-swallowed-cancellation.md) reports that clause until it rethrows
cancellation first; this rule does not repeat it.

## When it fires

- A catch parameter's type is exactly one of `types` (subtypes are not matched: catching
  `IllegalStateException` is a decision).
- The clause neither throws its parameter anywhere nor ends in a `throw`.
- The clause is not a forwarding catch (below).
- Under `report: swallowed`, the clause never reads its parameter, or reads it only as an
  argument of a function in `loggers`.
- At a failure boundary, under either `report` setting, only when the clause never reads its
  parameter.

The message says what the clause takes besides the failure it was written for: bugs, and in a
suspend context cancellation as well, unless a clause before it rethrows `CancellationException`.
A suspend context is the one [No swallowed cancellation](no-swallowed-cancellation.md) checks: the
closest enclosing function is `suspend`, or the closest lambda has a suspend function type (a
launched coroutine's body), seen through inline lambdas. Elsewhere no cancellation reaches the
clause, and the message does not mention one:

```
[Kotrail] Catching 'Exception' handles every failure alike, bugs included, and the failure goes no further than this clause. ...
[Kotrail] Catching 'Exception' handles every failure alike, bugs and cancellations included, and the failure goes no further than this clause. ...
```

## When it stays quiet

- The caught type is specific.
- The clause rethrows: `throw e` anywhere in it, or a `throw` of anything as its last
  statement (a wrapped rethrow).
- A forwarding catch, under either `report` setting: every statement of the clause hands the
  parameter whole to a call that is not a logger (`onError(e)`, `report(e)`), assigns it whole
  (`failure.value = e`, `_error.value = e`), or throws it, bare or passed to a constructor
  (`throw e`, `throw RenderFailed(e)`), and nothing else in the clause reads it: no `is` check,
  no `when (e)`, no `e.message` (reading the message to log it is inspecting, not forwarding).
  An error boundary hands the exception whole to whoever records it; nothing is swallowed or
  narrowed by hand, so there is no reflex to report.

  ```kotlin
  try {
      scene.render(frame)
  } catch (e: Throwable) {
      failure.value = e               // quiet: forwarded whole
  }
  ```

  The exemption is this rule's alone and says nothing about cancellation: in a suspend context
  a forwarding clause that does not rethrow still swallows the `CancellationException`, and
  [No swallowed cancellation](no-swallowed-cancellation.md) still reports it until the clause
  rethrows cancellation first.
- An empty `Throwable` or `Exception` clause (nothing in it but comments) around a callee that
  records before rethrowing: the try body is a single call, or a call chain whose outermost call
  (`scene?.render(canvas)` included) is to such a function. The callee has handed the failure on
  already; the caller's clause only keeps it from going further.

  ```kotlin
  fun render(canvas: Canvas) {
      try {
          draw(canvas)
      } catch (e: Throwable) {
          failure.value = e             // records
          throw e                       // then rethrows
      }
  }

  try {
      scene.render(canvas)
  } catch (e: CancellationException) {
      throw e
  } catch (_: Throwable) {
      // quiet: render() recorded the failure before rethrowing it
  }
  ```

  A function records before rethrowing (the inferred fact `recordsFailure`) when its whole body
  is one `try`, as a block body of that single statement or as an expression body, and its
  clause for `Throwable` or `Exception` is a forwarding catch (as above) of at least two
  statements whose last is the rethrow, bare or wrapped (`throw e`, `throw RenderFailed(e)`).
  Its other clauses must let nothing else out unrecorded: each catches `CancellationException`,
  records and rethrows the same way, or throws nothing. A clause that only rethrows records
  nothing, and a function that does work before or after its `try` lets that work's failures
  out unrecorded; neither has the fact. A `finally` block does not count.

  In a suspend context the empty clause also takes cancellation, so it is accepted only after
  a clause that rethrows `CancellationException`, as in the example; without one it is reported.
  The exemption covers `Throwable` and `Exception` clauses only: an empty `Error` or
  `RuntimeException` clause is still reported, and so is a try body of more than the one call.

  Within a module the callee's body is analyzed. For a callee in another module the plugin
  writes the fact where the callee is compiled, while this rule is on there: into the module's
  facts file on the JVM and Android (`"recordsFailure": {}`), as the marker annotation
  `@InferredRecordsFailure` in a klib. See [Inferred metadata](../inferred-metadata.md). A
  library compiled without Kotrail, or with `inferredMetadata = false`, has no fact, and an
  empty clause around its functions is reported. There is no hand-written form of the fact.
- Under `report: swallowed`, the parameter reaches anything but a logger: a return value, a
  property, a callback, a constructor, a string template.
- The `try` is at a failure boundary (above) and the clause reads its parameter: a log line is
  where a failure belongs at the top of a coroutine, a listener or a thread.
- The catch parameter carries the suppression: `catch (@Suppress("KOTRAIL_CATCH_TOO_BROAD") e: Exception)`
  names exactly one clause, where `@Suppress` on the enclosing function or `try` would cover every
  clause in it.
- The location matches the rule's `exclude` predicate. A project's error boundaries that are
  not [failure boundaries](#failure-boundaries) (a request handler, a plugin host) are
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
[Unguarded boundary](unguarded-boundary.md) covers the other half of "narrow inside, broad at
boundaries": a failure boundary whose catches are all narrow.

## Fixtures

`compiler-tests/testData/diagnostics/catchTooBroad.kt` (with its messages in `catchTooBroad.diag.txt`),
`compiler-tests/testData/diagnostics/catchTooBroadForwarding.kt` (forwarding catches under `report: all`),
`compiler-tests/testData/diagnostics/catchTooBroadRecordingCallee.kt` (empty clauses around a callee that records before rethrowing),
`compiler-tests/testData/diagnostics/catchTooBroadAcrossModules.kt` (the same fact read from a library's facts file),
`compiler-tests/testData/diagnostics/catchTooBroadBoundaries.kt` (failure boundaries)

## Implementation notes

`fir/checkers/CatchTooBroadChecker.kt`, a `FirTryExpressionChecker`. The rethrow test is a
visitor over the clause for a `throw` of the parameter, plus a look at the last statement. The
swallowed test is a visitor over the clause's reads of the parameter, each checked against the
innermost enclosing call: only reads that are arguments of a `loggers` function are allowed.
The forwarding test looks at each statement of the clause for the parameter as a direct argument
of a call outside `loggers`, the right-hand side of an assignment, or a thrown value (bare or a
direct argument of the thrown call), and then counts every read of the parameter in the clause:
a read beyond those forwarded ones means the clause inspects it.

The rethrow, forwarding and caught-type tests live in `fir/errors/CatchClauses.kt`, shared with
the `recordsFailure` fact and [Unguarded boundary](unguarded-boundary.md). The `recordsFailure`
fact is `fir/errors/RecordsFailureService.kt`, an `InferredFactService` that reuses the
forwarding test; `InferredFacts.RecordsFailure` writes it. The suspend-context test, for the
empty clause around a recording callee and for the message's wording, is the one
[No swallowed cancellation](no-swallowed-cancellation.md) uses (`isSuspendContext`).

The failure-boundary test is `fir/errors/FailureBoundaries.kt`, shared with
[Unguarded boundary](unguarded-boundary.md#implementation-notes), and run only for a clause that
would otherwise be reported.

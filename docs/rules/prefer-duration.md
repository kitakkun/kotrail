# Prefer duration

**Diagnostic:** `KOTRAIL_PREFER_DURATION` (error, on the number)
**Key:** `rules.preferDuration` (on by default)
**Settings:** `test.annotations`

## What it rejects

A bare number handed to a function that also takes the same value as a `kotlin.time.Duration`:

```kotlin
delay(500)                                  // reported: delay(duration: Duration) exists
withTimeout(5_000) { fetch() }              // reported
query.debounce(300)                         // reported
backoff(250)                                // reported when the project also declares backoff(interval: Duration)
```

## What it asks for

```kotlin
delay(500.milliseconds)

val PollInterval = 500.milliseconds
delay(PollInterval)
```

A number makes the reader look up the callee's signature to learn the unit, and then guess why
it is that number. `500.milliseconds` says the unit; a named constant says the intent, and is
the one place to change when the policy changes. The callee already offers the `Duration` form,
so the rewrite is a different overload of the same call, not a conversion: the diagnostic names
the form to write, `500.milliseconds`, or the unit the parameter's name ends in
(`pause(timeoutSeconds = 5)` suggests `5.seconds`).

IntelliJ's K2 inspection "Convert Long to Duration" proposes the same rewrite in the editor for a
fixed list of coroutines functions. This rule enforces it in the build, where an assistant's code
meets it too, and covers any function with a `Duration` overload, the project's own included.
It reports only literals: a named constant already says what it is.

## When it fires

- An integer literal greater than zero (`500`, `5_000`, `1500L`) is passed directly as an
  argument whose parameter is an `Int` or a `Long`, and
- the callee has a `Duration` overload: a function of the same name in the same package or
  class, visible from the call and not hidden by a deprecation, with the same extension
  receiver (or none), the same number of parameters, a `kotlin.time.Duration` parameter at the
  literal's position, and every other parameter of the same type.

That covers `kotlinx.coroutines.delay`, `withTimeout`, `withTimeoutOrNull`, `Flow.debounce`,
`Flow.sample`, and any project function, member or top-level, that offers both forms.

## When it stays quiet

- The argument is a `Duration`: that is the fix.
- The argument is anything but a literal: a constant, `PollInterval.inWholeMilliseconds`, a
  parameter passed through (`delay(interval)`). It already has a name.
- The literal is `0` or negative: nothing waits, and there is no unit to state.
- The callee has no `Duration` overload: `Thread.sleep(1500)`, `TimeUnit.SECONDS.sleep(5)`,
  `process.waitFor(15)`, `future.get(10)`, or a project function that takes only a number
  (`poll(interval = 250)`). Writing `Thread.sleep(200.milliseconds.inWholeMilliseconds)` would
  read worse than the number.
- The function of the same name that takes a `Duration` is not the same call with a unit: it
  differs in another parameter or in its receiver, is private to a class the call is outside of,
  or is hidden by `@Deprecated(level = HIDDEN)`.
- The call also passes a `java.util.concurrent.TimeUnit` or a `java.time.temporal.ChronoUnit`:
  `process.waitFor(15, TimeUnit.SECONDS)`, `builder.callTimeout(10, TimeUnit.SECONDS)`. The
  unit is written beside the number.
- The callee is a Java method, which never takes a `kotlin.time.Duration`.
- The call is in a test function (`test.annotations`) or a test source set (`src/test`,
  `src/commonTest`, `src/jvmTest`, ...): a test's waits belong to
  [No sleep in tests](test/no-sleep.md).

## Related rules

[Delay for completion](delay-for-completion.md) reports a `delay` that stands in for waiting on
work that was started; this rule reports how any wait's length is written.
[No sleep in tests](test/no-sleep.md) reports real-time waits in tests, which this rule leaves
alone.

## Fixtures

`compiler-tests/testData/diagnostics/preferDuration.kt`, its messages in
`preferDuration.diag.txt` (`preferDuration.fir.diag.txt` for the test framework before Kotlin 2.4.20).

## Implementation notes

`fir/checkers/PreferDurationChecker.kt`, a function call checker: it walks the call's resolved
argument mapping, skips the call when an argument's type is `TimeUnit` or `ChronoUnit`, and for
each positive integer literal passed to an `Int` or `Long` parameter looks for the `Duration`
overload among the functions of the same name in the callee's package (top-level) or class
scope (members). Parameter types are compared by class and type arguments, with the function's
own type parameters compared by position, since each overload declares its own; visibility goes
through the compiler's visibility checker from the call's file and containing declarations.

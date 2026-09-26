# Fire-and-forget launch

**Diagnostic:** `KOTRAIL_FIRE_AND_FORGET_LAUNCH` (error, on the function name)
**Key:** `rules.fireAndForgetLaunch` (**off by default**)
**Settings:** none of its own; `starters` is shared with [delay for completion](delay-for-completion.md)

## What it rejects

A non-suspending function that starts work and gives its caller nothing to wait for:

```kotlin
fun reconnect() {
    scope.launch { socket.reopen() }     // reported: the Job is dropped
}
```

## What it asks for

```kotlin
fun reconnect(): Job = scope.launch { socket.reopen() }   // the caller can join it

suspend fun reconnect() { socket.reopen() }               // or awaits it
```

A dropped handle is what forces callers into `delay(500)` guesses; [delay for
completion](delay-for-completion.md) reports those, this rule reports the function that made
them necessary. It is off by default: an event handler that launches and returns is the ordinary
shape of UI code, and the rule is for the modules (services, repositories, a host's connection
management) where a caller needs to know when the work is done.

## When it fires

The function is not `suspend`, has a body, and that body calls one of `starters` as a
statement, dropping the result, through `if`, `when` and `try` but not through lambdas. Only
the body's own starter calls count: a function that merely calls `reconnect()` is not reported
here (the message should name the declaration to fix), though [delay for
completion](delay-for-completion.md) follows such calls.

## When it stays quiet

- The starter's result is returned or kept (`val job = scope.launch { }`).
- The function is `suspend`, private, or local: its callers are in reach.
- The function is an `override` (the signature is fixed elsewhere), `main`, or named `on...`
  (`onClick`, `onResume`: an event handler).
- A starter inside the lambda of another starter: reported once, on the outer one.

## Fixtures

`compiler-tests/testData/diagnostics/fireAndForgetLaunch.kt`

## Implementation notes

`fir/checkers/FireAndForgetLaunchChecker.kt`, a `FirNamedFunctionChecker`. Whether or not the
rule is on, it warms the session's `AsyncWorkService` for every function it sees, so that the
metadata writer of `@InferredStartsAsyncWork` and cross-module callers find the answer cached.

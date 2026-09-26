# Delay for completion

**Diagnostic:** `KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK` (error, on the wait)
**Key:** `rules.delayForCompletion` (on by default)
**Settings:** `delays` (default `[kotlinx.coroutines.delay, java.lang.Thread.sleep, android.os.SystemClock.sleep]`), `starters` (default: `launch`, `async`, `launchIn`, `Thread.start`, `thread`, `Handler.post`, `Handler.postDelayed`, `Executor.execute`, `ExecutorService.submit`, `Timer.schedule`)

## What it rejects

A fixed wait that stands in for awaiting work a call just started:

```kotlin
fun Manager.reconnect() {
    scope.launch { socket.reopen() }     // starts work, drops the Job
}

suspend fun switch() {
    manager.reconnect()
    delay(500)                           // reported: guesses how long reconnect takes
    send(hello)
}
```

## What it asks for

A handle to wait on, instead of a number:

```kotlin
suspend fun Manager.reconnect() { socket.reopen() }        // the caller awaits it

fun Manager.reconnect(): Job = scope.launch { socket.reopen() }   // or joins it
```

Too short and the code after the wait runs before the work is done; too long and it waits for
nothing. Either way the number is a guess about another machine's speed, and it is the number
that gets bumped when the bug shows up again. The fix is always the same: make the function
`suspend`, or return its `Job` or `Deferred`, and `await` or `join` it. [Fire-and-forget
launch](fire-and-forget-launch.md) reports the producer side of the same pattern.

## When it fires

- The call is one of `delays` and its amount is fixed: a literal, a `const val`, a qualifier
  (`Duration.ZERO`), or a value built only from those (`500.milliseconds`, `Duration.ofMillis(500)`).
- Some earlier statement of the same block starts work the caller cannot wait for: a statement
  that calls one of `starters` and drops the result, or that calls a function which does so
  (through `if`, `when` and `try`, at any depth of calls, and across modules through the
  metadata below). Between that statement and the wait there is no suspending call and no other
  wait: those mean the wait is about something else.
- The wait sits in a function that is not a test (`test.noSleep` owns tests).

## When it stays quiet

- The amount is computed (`delay(timeout)`), so it is a parameter of the caller, not a guess.
- The wait is inside a loop body or the lambda of `repeat` or a `retry*` helper: polling has
  a reason to wait.
- The started work hands over a handle (`fun start(): Job`), or the function is `suspend`.
- Nothing was started before the wait in that block.

## How a function is known to start work

A function starts work its caller cannot wait for when it is not `suspend` and its body calls a
starter as a statement, dropping the result, or calls another such function. For a function in
the same module the body is analyzed; for one in another module the compiler plugin writes
`@InferredStartsAsyncWork` into its metadata (`com.kitakkun.kotrail.concurrency`), and the
calling module reads it. A library compiled without Kotrail carries no metadata and is taken as
not starting work.

## Fixtures

`compiler-tests/testData/diagnostics/delayForCompletion.kt`,
`compiler-tests/testData/diagnostics/delayForCompletionAcrossModules.kt`

## Implementation notes

`fir/checkers/DelayForCompletionChecker.kt`, a `FirFunctionCallChecker`; the "starts work"
question is answered by `fir/concurrency/AsyncWorkService.kt`, a session component memoized per
symbol, and written to metadata by `ir/concurrency/InferredStartsAsyncWorkMetadataWriter.kt`.

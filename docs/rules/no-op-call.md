# No-op call

**Diagnostic:** `KOTRAIL_CALL_DOES_NOTHING` (error, on the call)
**Key:** `rules.noOpCall` (on by default)
**Settings:** `test.annotations`
**Artifact:** `annotations` (`com.kitakkun.kotrail.noop.InferredNoOpWhen`, written by the plugin)

## What it rejects

A call whose arguments make the callee return at once, without doing anything:

```kotlin
fun send(bytes: ByteArray) {
    if (bytes.isEmpty()) return       // inferred: does nothing when bytes is empty
    socket.write(bytes)
}

send(byteArrayOf())                   // reported: 'send' returns at once when bytes.isEmpty().
delay(0)                              // reported: 'delay' returns at once when timeMillis <= 0. To let other coroutines run, call yield().
delay(Duration.ZERO)                  // reported
delay(-1)                             // reported
```

## What it asks for

Remove the call, or call what was meant. A call that can be seen to do nothing at compile time
is either dead code or a misunderstanding of the callee, and the reader cannot tell which.

`delay(0)` is the common misunderstanding: it is written to yield, and it does not.
`kotlinx.coroutines.delay` returns before it suspends when the time is not positive
(kotlinx-coroutines-core 1.11.0, `commonMain/Delay.kt`):

```kotlin
public suspend fun delay(timeMillis: Long) {
    if (timeMillis <= 0) return // don't delay
    return suspendCancellableCoroutine sc@ { cont: CancellableContinuation<Unit> ->
```

and the `Duration` form hands it 0 for a duration that is not positive:

```kotlin
public suspend fun delay(duration: Duration): Unit = delay(duration.toDelayMillis())

internal fun Duration.toDelayMillis(): Long = when (isPositive()) {
    true -> plus(999_999L.nanoseconds).inWholeMilliseconds
    false -> 0L
}
```

So `delay(0)` neither suspends, nor lets other coroutines run, nor checks for cancellation, which
happens in `suspendCancellableCoroutine` and is never reached. `yield()` does all three;
`ensureActive()` checks for cancellation alone.

## How the conditions are found

The rule reads an inferred fact, `noOpWhen`: the conditions under which a function returns at
once. For a function compiled from source it is read from the body:

- the function returns `Unit`, and no override can run in its place: it is final, or a member of
  a final class that is not an enum;
- its body opens with guards: `if (<condition>) return`, or a `when` without subject whose
  leading branches are `<condition> -> return`. Several guards in a row each count, and any one
  of them true makes the call do nothing; nothing else may precede them;
- a guard's branch holds a bare `return` (or `return Unit`) and nothing else;
- the condition fits the [precondition language](preconditions.md#how-contracts-are-found):
  parameters, constants, comparisons, `==` / `!=` (`null` included), `&&` / `||` / `!`,
  arithmetic, `isEmpty()` / `isBlank()` / `length` / `size`, and durations
  (`Duration.ZERO`, `500.milliseconds`). That language reads nothing but parameters and
  constants, so the guard has no side effect a call could rely on.

The first statement that is not such a guard ends the run; a guard whose condition does not fit
the language ends it too, since its condition may do work. A condition that mentions no parameter
is skipped. In an `if` with an `else`, or a `when` whose later branches do work, the leading
`return` branches still count, and the run ends there.

For a library function there is no body to read, so a small knowledge base fills in:

| Function | Does nothing when | Hint |
| --- | --- | --- |
| `kotlinx.coroutines.delay(timeMillis: Long)` | `timeMillis <= 0` | To let other coroutines run, call yield(). |
| `kotlinx.coroutines.delay(duration: Duration)` | `duration <= Duration.ZERO` | To let other coroutines run, call yield(). |

## How arguments are folded

As for [preconditions](preconditions.md#how-arguments-are-folded): literals, `const val`s, final
`val`s with a constant initializer, local `val`s, arithmetic, string templates, and the
defaults of parameters left out when the callee is compiled in the same module. For this rule
the evaluator also knows:

- durations: `Duration.ZERO`, `Duration.INFINITE`, `<number>.<unit>` through the
  `kotlin.time.Duration.Companion` properties (`0.milliseconds`, `5.seconds`), and their
  negation (`-1.seconds`, `(-1).milliseconds`);
- arrays, by their size: `byteArrayOf()`, `arrayOf(...)`, `emptyArray()`, `ByteArray(0)` and the
  other primitive array constructors. Arrays compare by identity, so `==` on one is never decided.

A condition is reported when every parameter it mentions is known and it holds. The message
names the callee and the condition with its parameter names, followed by the callee's hint
when the knowledge base gives one.

## Across modules

When a module is compiled, every non-private function with recorded conditions gets them
recorded for other modules (`"bytes.isEmpty()", ...`, durations as `Duration.ZERO` or
`500.milliseconds`): as a `noOpWhen` entry in the module's facts file on the JVM and Android, as
an `@InferredNoOpWhen` annotation in a klib (see [Inferred metadata](../inferred-metadata.md)).
Dependent modules read them and check their calls the same way. Written by hand on a
declaration, the annotation takes precedence over its body.

## When it stays quiet

- An argument the condition depends on is not known: `send(payload)`, `delay(timeout)`.
- The condition is false for the arguments: `send(byteArrayOf(1))`, `delay(10)`.
- The callee returns a value: `fun count(bytes: ByteArray): Int { if (bytes.isEmpty()) return 0 ... }`.
  The result is the point of the call.
- The guard is not the first statement, or something else precedes it.
- The guard does work before `return` (`if (bytes.isEmpty()) { log("empty"); return }`).
- The callee can be overridden (`open`, `abstract`, an interface member): an override may do
  something. The fact belongs to the declaration called: an override is read on its own body,
  under the same conditions, for the calls that resolve to it.
- The call is in a test function (`test.annotations`) or a test source set.
- The call is compiler-generated.

## Related rules

[Preconditions](preconditions.md) reports arguments that make the callee fail; this rule
reports arguments that make it do nothing. [Prefer duration](prefer-duration.md) reports how a
positive wait is written and leaves `delay(0)` to this rule.

## Fixtures

`compiler-tests/testData/diagnostics/noOpCall.kt` and
`compiler-tests/testData/diagnostics/noOpCallAcrossModules.kt`, their messages in `*.diag.txt`
(`*.fir.diag.txt` for the test framework before Kotlin 2.4.20);
`compiler-tests/testData/box/noOpCall/inferredMetadataAcrossModules.kt` for the facts written.

## Implementation notes

`fir/noop/NoOpService.kt` is the `noOpWhen` fact on the shared base described in
[Inferred metadata](../inferred-metadata.md): for a declaration of another module, the facts
file's entry, then the annotation, hand-written or inferred, which also wins over the body of a
source declaration, then `NoOpNames.KNOWN_FUNCTIONS`, then the guards read with the
preconditions' `CondConverter`. The shared writer records the conditions (the facts file on the
JVM, `@InferredNoOpWhen` in a klib). `fir/noop/checkers/NoOpCallChecker.kt` folds the arguments with the same
`knownArgumentValues` the precondition checker uses and evaluates the conditions with the shared
`Cond` evaluator, which holds `Duration` values as `kotlin.time.Duration` so that conversions and
comparisons are Kotlin's own.

# No async work in initializer

**Diagnostic:** `KOTRAIL_ASYNC_WORK_IN_INITIALIZER` (error, on the call that starts the work)
**Key:** `rules.noAsyncWorkInInitializer` (on by default)
**Settings:** `functions` (default: `kotlinx.coroutines.launch`, `async`, `flow.launchIn`, `kotlin.concurrent.thread`, `java.lang.Thread.start`, `Executor.execute`, `ExecutorService.submit`, `android.os.Handler.post`, `postDelayed`, `java.util.Timer.schedule`, `scheduleAtFixedRate`, the `kotlin.concurrent` `schedule`, `scheduleAtFixedRate`, `timer` and `fixedRateTimer`, `SwingUtilities.invokeLater`, `EventQueue.invokeLater`)
**Fix:** none (where the work starts, and who starts it, is the owner's decision)

## What it rejects

Asynchronous work (a coroutine, a thread, a posted task) started from inside a property
initializer, where starting it is not what the property holds:

```kotlin
class Captures(private val scope: CoroutineScope) {
    private val requests = Channel<Request>(Channel.UNLIMITED).also { requests ->
        scope.launch(Dispatchers.IO) {            // reported
            for (request in requests) handle(request)
        }
    }
}
```

## What it asks for

The loop as a named function, and the start in a place the reader looks for it: `init`, or a
`start()` the owner calls:

```kotlin
class Captures(private val scope: CoroutineScope) {
    private val requests = Channel<Request>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) { consume(requests) }
    }

    private suspend fun consume(requests: ReceiveChannel<Request>) {
        for (request in requests) handle(request)
    }
}
```

The reader of `requests` sees a channel and never learns that a consumer loop runs behind it.
The work starts at a time nobody chose: during construction, before the properties declared
below it are initialized (the loop can read one of them as `null`), and before the owner has
decided anything. Nothing keeps the `Job`, the `Thread` or the `Future`, so nothing can stop the
work or see it fail. A test that only constructs the object starts it. Written in `init` or in a
`start()`, the same call is part of the visible construction code or of an explicit decision,
and the value the property holds is just the value.

## When it fires

- The property is a member of a class, an `object` or a companion, or a top-level property. Its
  initializer, the initializer of its explicit backing field (`field = ...`), or its delegate
  expression contains a call to one of `functions`, matched as globs against the callee's fully
  qualified name.
- `by lazy { ... }` counts its lambda as the initializer: the work then starts on first access,
  which is even harder to foresee than construction.
- The call is found at any depth of lambdas that an inline function runs in place: `also`,
  `apply`, `let`, `run`, `with`, `takeIf`, `buildList`, `forEach`, any inline function's lambda
  that is neither `noinline` nor `crossinline`.
- The call is not the property's value (below).

The message names the property, the function that starts the work, and the class being built
(`Captures`, `Captures.Companion`), or the file for a top-level property, whose initializers run
when the file is first used.

## When it stays quiet

- The call is the property's value: the type then says that work begins, and the reader is
  told. `val job = scope.launch { }`, `val job = events.onEach { }.launchIn(scope)`,
  `val worker = thread { }`, `val job by lazy { scope.launch { } }` (the lazy lambda's result is
  the value). The value is followed through safe calls (`scope?.launch { }`), `!!`, the branches
  of `if`, `when`, `try` and `?:`, the receiver of `also` and `apply`
  (`scope.launch { }.also { log("started") }`), and the lambda result of `let`, `run` and `with`.
- The call sits in a lambda that runs later: one handed to a non-inline function
  (`val onClick = { scope.launch { } }`, `Runnable { scope.launch { } }`), a `noinline` or
  `crossinline` parameter (`flow.map { }` runs its transform when the flow is collected), a local
  function, an anonymous object's members.
- The property is a local variable, in a function body, a lambda or an `init` block: its
  initializer runs when that code runs, which the reader sees.
- The call is in an `init` block or a constructor body: visible construction code.
- `stateIn` and `shareIn` are not in the default list: what they start is the sharing that the
  resulting `StateFlow` or `SharedFlow` names.
- A member of an anonymous object (`object : Listener { val x = ... }`) is not looked at.
- The file is in a test source set (`src/test/`, `src/jvmTest/`, `src/androidUnitTest/` and the
  like): a test builds its fixtures however it needs. A test function is irrelevant here, since
  only properties are checked.

## Related rules

[Delay for completion](delay-for-completion.md) reports a fixed wait for work a call started and
dropped; this rule reports the start that nobody sees in the first place.

## Fixtures

`compiler-tests/testData/diagnostics/noAsyncWorkInInitializer.kt`

## Implementation notes

`fir/checkers/NoAsyncWorkInInitializerChecker.kt`, a `FirPropertyChecker`. The checker first
collects the expressions whose result is the property's value (the initializer itself and the
shapes listed above), then walks the initializer and records every call to a configured function
outside that set. A lambda is walked only when its inline status is `Inline`, which the compiler
sets for a lambda bound to a parameter of an inline function that is neither `noinline` nor
`crossinline`; a crossinline lambda may be invoked from another execution context, which is what
the deferred APIs written that way (`Flow.map`, Android KTX `doOnLayout`) do. Local functions,
accessors, anonymous objects and local classes are not walked. The callee is matched by its own
fully qualified name: an override declared in a subclass (`ThreadPoolExecutor.execute`) has its
own name, to be added to `functions` or matched with a glob (`java.util.concurrent.*.execute`).

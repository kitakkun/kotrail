# Unguarded boundary

**Diagnostic:** `KOTRAIL_BOUNDARY_UNGUARDED` (error, on the first catch parameter)
**Key:** `rules.unguardedBoundary` (on by default)
**Settings:** `functions` (root launchers; default: `launch`, `launchIn`, `stateIn`, `shareIn`, `LaunchedEffect`, `DisposableEffect`, `thread`, the `Thread` constructor, `Executor.execute`, `ExecutorService.submit`, `Handler.post`, `Handler.postDelayed`, `SwingUtilities.invokeLater`, `EventQueue.invokeLater`, `Timer.schedule`), `annotations` (default empty), `names` (default empty), `guardedScopes` (default empty). The first three define the failure boundaries of [Catch too broad](catch-too-broad.md) as well.

## What it rejects

A `try` at a failure boundary whose catch clauses all name narrow types, around a call into code
the module does not declare:

```kotlin
val requests = Channel<Request>(UNLIMITED).also {
    scope.launch {
        for (request in it) {
            try {
                client.send(request)
            } catch (e: IOException) {          // reported
                log.warn("send failed", e)
            }
        }
    }
}
```

`client.send` can fail in ways the clause does not name: a serialization error, an
`IllegalStateException` from a closed client, a bug. Any of them leaves the `for` loop, ends the
launched coroutine, and the channel goes on filling with requests nobody reads. Nothing in the
code above the `try` receives the failure: only the default handler sees it, which logs it (on
Android, it ends the process), and the work stops.

## What it asks for

A broad clause after the narrow ones that logs or reports what nothing else names, or a
`CoroutineExceptionHandler` on the scope:

```kotlin
try {
    client.send(request)
} catch (e: IOException) {
    log.warn("send failed", e)
} catch (e: Exception) {
    log.error("request failed", e)              // the loop survives, and the failure is seen
}
```

Narrow inside, broad at boundaries. A broad catch in the middle of the logic hides a bug from a
caller that would have seen it, which is what [Catch too broad](catch-too-broad.md) reports. At
the top of a unit of work there is no such caller, and a narrow catch is the risk: whatever it
does not name ends the coroutine, the consumer loop, the listener or the thread, and only the
default handler sees it. Catch too broad accepts a broad clause there as long as it reads the
exception, so the two rules point the same way.

## Failure boundaries

A `try` is at a failure boundary when it is a statement at the top of a root body: the body is
the `try`, or statements and the `try`, and a `try` inside an `if`, a `when`, a loop or another
`try` directly in that body counts too (a loop around a `try` is the usual consumer). The
lambdas of inline functions (`apply`, `also`, `let`, `run`, `with`, `use`, `forEach`, `repeat`,
any inline function's lambda) run in place and are seen through in both directions: the root is
found through them, and a `try` inside one counts as a statement of the root. Any other lambda,
a nested function, a class, a constructor, an `init` block or a property initializer is not a
root itself, and a `try` inside it is not at the top of one. A root body is:

1. The lambda passed to a root launcher, a call whose callee's fully qualified name matches
   `functions` (globs; for a member reached through a subtype, the member it copies counts
   too). For `launchIn`, `stateIn` and `shareIn` the lambda is not an argument: the flow chain
   that is the receiver of the call is the root, so the lambdas of `flow { }`, `callbackFlow { }`,
   `channelFlow { }`, `onEach { }`, `map { }` and the other operators in that same chain
   expression are root bodies.
2. The body of a function that overrides a member declared outside the module and returning
   `Unit` (a library listener, `Runnable.run`, `Thread.run`, a plugin entry point), and a lambda
   that returns `Unit`, converted to an interface declared outside the module
   (`view.setOnClickListener { }`, `Runnable { }`, the `Runnable` of `executor.execute { }`).
   These are fire-and-forget callbacks: the library calls them and nothing receives what escapes.
   An override that returns a value is not a root (`ClassLoader.loadClass`, a command handler
   that returns its result, `Callable.call`): its caller consumes the result and receives the
   failure with it. The return type is the member's as the class sees it, so `accept` of a
   `Consumer<Event>` is a root. The members of `Any` are not roots either: `toString`, `equals`
   and `hashCode` are called by the project's own code.
3. The body of a function the project declares a boundary: annotated with one of `annotations`
   (globs over fully qualified names), a member of a class annotated with one, or named by
   `names` (globs over fully qualified function names, `com.acme.Dispatcher.dispatch`).

Not roots: `async` (the failure goes to whoever awaits it), `withContext`, `coroutineScope`,
`supervisorScope` and `runBlocking` (they return it to their caller), `runCatching` (it catches
everything, so a `try` inside it is not at a boundary at all), and a `flow { }` collected
elsewhere. A root does not depend on where it sits: a `launch` in a property initializer (the
`Channel(...).also { scope.launch { ... } }` above), an `init` block, a constructor or a function
body starts a root all the same.

"Declared outside the module" means neither compiled from source in this compilation nor loaded
back by an incremental compilation: a library, or another module of the same build.

## When it fires

- The `try` is at a failure boundary.
- It has catch clauses, and none of them names one of `catchTooBroad.types` (`Throwable`,
  `Exception`, `RuntimeException`, `Error` by default; matched exactly, as there).
- Not every clause rethrows.
- The try block, outside nested non-inline lambdas, local functions and local classes, runs
  code the project does not declare: it calls a function declared outside the module whose
  package is not `kotlin` or `kotlinx.coroutines` (or below them), or it invokes a function-typed
  value (`block()`, `capture(frame)`, a function-typed parameter or property), whose code is
  unknown at this point. That is the code most likely to throw something unexpected. The
  constructor of a data class or a value class declared outside does not count: it only holds
  the values it is given. The constructor of any other class counts, since its `init` can run
  anything.
- A call to one of the module's own functions counts when that function reaches such a call: its
  body is searched the same way, and so are the bodies of the own functions it calls, up to three
  functions deep, each entered once (a recursive call is not followed again). So
  `writeAndRecord(SetViewAttribute(...))`, where `writeAndRecord` is a private function of the
  module that sends through a messenger declared outside, counts for the messenger's call, while
  an own function that does local work alone does not count:

  ```kotlin
  scope.launch {
      try {
          writeAndRecord(SetViewAttribute(nodeId, value))
      } catch (e: MessagingException) {        // reported, naming 'writeAndRecord' and 'Messenger.request'
          log.warn("write failed", e)
      }
  }

  private suspend fun writeAndRecord(command: Command) {
      messenger.request(command)
      history += command
  }
  ```

  Only bodies in this compilation's sources are followed. An own function loaded from a class
  file, as an incremental compilation loads the files it does not compile again, has no body at
  hand and counts as none of these, so such a boundary goes quiet in a build that compiles its
  file without the callee's, until a build compiles both again.

The message reads: *This catch at a boundary handles only 'IOException': any other failure from
'OutputStream.write' ends 'launch' here, and only the default handler sees it. Catch Exception as
well and log or report it, or hand the scope a CoroutineExceptionHandler.* The last clause is
there for a coroutine root only; a listener or a thread reads *... ends 'onClick' here, and only
the default handler sees it. Catch Exception as well and log or report it.*

The message names the call that can throw: the first that invokes a function-typed value
(`capture(...)`) or calls a function declared outside the module other than a constructor or a
getter (`get...()`, `is...()` with no parameters), outside `kotlin` and `kotlinx.coroutines`,
or calls one of the module's own functions that reaches such a call. The latter is named with
the call it reaches: *any other failure from 'writeAndRecord' (which calls 'Messenger.request')
ends 'launch' here*.
First is by where the callee's name starts, so a call comes before the calls in its arguments:
`capture(options ?: CaptureOptions())` names `capture(...)`, and
`System.setProperty(key, System.getenv(key))` names `System.setProperty`. The calls in its
receiver come before it: for `Image.makeFromEncoded(png).close()` that is `makeFromEncoded`,
which decodes, and not `close`. A member of a companion object is named by its class
(`Image.makeFromEncoded`). A constructor or a getter is weighed last and named only when the
block calls nothing else; `writeAndRecord(StringBuilder(label))` around a project's own
`writeAndRecord` that reaches no call out names `StringBuilder`.

## When it stays quiet

- A clause names a broad type: the boundary is guarded (Catch too broad then asks it to read
  the exception).
- Every clause rethrows: the `try` cleans up or translates and was not written to stop failures.
- The `try` sits in the try block of another `try` with a broad clause, at the same root.
- The root is a launcher whose receiver or arguments, outside lambdas, carry a
  `CoroutineExceptionHandler`: `scope.launch(handler) { }`,
  `launch(Dispatchers.IO + handler) { }`, `CoroutineScope(SupervisorJob() + handler).launch { }`.
  A handler installed on a scope declared elsewhere is not seen; name such a scope in
  `guardedScopes`.
- The launcher runs in a scope `guardedScopes` names: its receiver or one of its arguments
  (`pluginScope.launch { }`, `events.onEach { }.launchIn(pluginScope)`) reads a property, or
  calls a function, whose fully qualified name matches one of the globs, directly or through a
  local `val` initialized that way (one step: `val scope = pluginScope; scope.launch { }`). The
  setting is for a framework that hands out a scope with a `CoroutineExceptionHandler` already
  installed:

  ```yaml
  rules:
    unguardedBoundary:
      guardedScopes: [com.example.HostPlugin.pluginScope, "com.example.*.safeScope"]
  ```
- The root is an override, or a converted lambda, that returns a value: not a root at all (see
  [Failure boundaries](#failure-boundaries)).
- The root is a flow chain with a `.catch { }` operator downstream of the try's lambda:
  `events.map { try { ... } catch (e: IOException) { ... } }.catch { ... }.stateIn(...)`.
- The try block calls only the project's own code that reaches no call out within three
  functions, the standard library, or kotlinx.coroutines, and invokes no function-typed value.
  Constructors of data classes and value classes declared outside count as none of these
  (`capture(last ?: CaptureOptions())` around the project's own `capture`).
- The `try` is anywhere but at the top of a root: in an ordinary function, a property
  initializer, an `init` block, or the lambda of `async` or `withContext`.

Not considered: what the called code is declared to throw (`@Throws`, Java `throws`). A clause
naming exactly the exceptions a Java method declares is still reported, since an unchecked
exception ends the root the same way. A property read that runs a Java getter is not counted as
a call. A data class whose `init` validates its values (`require(depth > 0)`) is presumed not to
throw like any other data class: its declaration is in another module, and only its kind is
looked at.

## Related rules

[Catch too broad](catch-too-broad.md) is the other half: broad catches away from boundaries.
[No swallowed cancellation](no-swallowed-cancellation.md) still applies to the broad clause this
rule asks for in a coroutine: rethrow `CancellationException` first.

## Fixtures

`compiler-tests/testData/diagnostics/unguardedBoundary.kt`, with its messages in
`unguardedBoundary.diag.txt` (own functions followed to a call out in `Inspector`); constructors
of classes declared in another module in
`unguardedBoundaryAcrossModules.kt` and `unguardedBoundaryAcrossModules.diag.txt`; the boundary
definition from the other side in `compiler-tests/testData/diagnostics/catchTooBroadBoundaries.kt`.

## Implementation notes

`fir/checkers/UnguardedBoundaryChecker.kt`, a `FirTryExpressionChecker`. The boundary is found
by `fir/errors/FailureBoundaries.kt`, shared with Catch too broad. It walks the checker's path of
enclosing elements from the `try` upward, through blocks, `when`, loops, tries, catches,
returns, local properties, assignments, safe calls, elvis expressions and receiver chains, and
through the lambdas of inline calls (`InlineStatus.Inline`), until it reaches a lambda or a
function. A lambda is a root when its call matches `functions` (by the callee's fully
qualified name, or that of the member a fake override copies), when it is passed for a
parameter whose type is an interface declared outside the module or to that interface's SAM
constructor, or when its call is a link of a flow chain (each link the receiver of the next,
each of a type from `kotlinx.coroutines.flow`) that ends in a call matching `functions`; the
links in between are the downstream operators. A converted lambda counts only when its
resolved return type is `Unit`. A function is a root when one of the functions it overrides
(`overriddenFunctions`), unwrapped from fake overrides, is declared outside the module and is
not a member of `Any`, and returns `Unit` as the class sees it (the overridden symbol's
substituted return type), or when `annotations` or `names` name it. The walk also collects the
tries whose try block it passes, for the enclosing broad catch. The calls out are a visitor over
the try block that skips non-inline lambdas, local functions and classes, and SAM constructors;
it looks at every `FirFunctionCall`, an implicit `invoke` included, in `visitElement`, since the
visitor does not route `FirImplicitInvokeCall` through `visitFunctionCall`. An `invoke` whose
receiver has a function type (`isSomeFunctionType`, which covers Compose's function kinds) is a
function-typed invocation. A constructor whose class (`resolvedReturnType.toRegularClassSymbol`)
is a data class or a value class is not a candidate. A call to an own function, unwrapped from
fake overrides, whose origin is `FirDeclarationOrigin.Source` (so not one loaded back by
incremental compilation, `isPrecompiled`, nor a Java source) and that is not already being
searched, is a candidate when the same visitor finds one in its body (`symbol.fir.body`), at
most three functions deep; it takes that candidate's name and preference. The call named is the
first preferred
candidate (a function-typed invocation, or a call out that is neither a constructor nor a
getter) by the start of its callee reference, and only without one the first constructor or
getter.
`guardedScopes` is matched against the callee of the launcher's receiver and arguments, by its
own fully qualified name and that of the member a fake override copies.

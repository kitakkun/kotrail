# Idempotent

**Diagnostics:** `KOTRAIL_NOT_IDEMPOTENT` (error, on the operation a second call would repeat), `KOTRAIL_CALLEE_NOT_KNOWN_IDEMPOTENT` (error, on a call, only with `strict`)
**Key:** `rules.idempotent` (on by default; inert until a function is annotated)
**Settings:** `annotations` (default `[com.kitakkun.kotrail.idempotency.Idempotent]`), `allow` (default `[]`), `deny` (default `[]`), `strict` (default `false`)
**Artifact:** `annotations` (`com.kitakkun.kotrail.idempotency.Idempotent`)

## What it is for

A retry, a duplicate event, a second `refresh()` from a screen that appeared twice: much code
is called again with the same arguments and is only correct if the second call changes nothing
the first did not. Whether a function is meant to be safe to repeat is a decision no analysis
can read off the code; once it is written on the function, keeping to it is checkable:

```kotlin
import com.kitakkun.kotrail.idempotency.Idempotent

@Idempotent
fun refresh(items: List<Item>)
```

The annotation is a contract: calling the function twice in a row has the same effect as
calling it once. It goes on a function or a property setter (`@Idempotent set(value) { }`),
lives in the `kotrail-annotations` artifact, a multiplatform library the Gradle plugin adds as
`compileOnly`, and has binary retention, so a library's `@Idempotent` functions count as known
in its callers' `strict` checks. A project's own annotation is listed under `annotations`.

## What it rejects

```kotlin
@Idempotent
fun refresh(items: List<Item>) {
    refreshes++                          // reported: 'refreshes++' would update 'refreshes' twice
    total += items.size                  // reported: a compound assignment reads the previous value
    label = "$label!"                    // reported: so does `x = x + …`, `x = !x`, `xs[i] = xs[i] + 1`
    history.add(items)                   // reported: 'history.add' would add twice
    log.append("refreshed")              // reported: 'log.append' would append twice
    events.send(Refreshed)               // reported: 'events.send' would send twice
    scope.launch { load() }              // reported: 'scope.launch' would start the work twice
    lastRefresh = Clock.System.now()     // reported: would store a different value in 'lastRefresh'
}
```

The message names the operation, the function and what the second call would do:

```
'history.add' is not idempotent: calling 'refresh' twice would add twice. Remove @Idempotent,
or make the write idempotent (use a Set, or set(index, …) instead of add).
```

## What it asks for

```kotlin
@Idempotent
fun refresh(items: List<Item>, now: Instant) {
    shown = items                        // the same value again
    total = items.size                   // computed, not accumulated
    seen.add(items.first().id)           // a Set: adding again changes nothing
    byId[items.first().id] = items.first()
    cache = cache ?: load()              // settles after the first call
    flags = flags or VISIBLE
    state.value = Loaded(items)          // a MutableStateFlow drops an equal value
    lastRefresh = now                    // a varying value taken as a parameter
}
```

## What counts as repeating

The rule looks at writes to state the function does not own: a property, the backing `field`
of an annotated setter, a variable captured from an enclosing function, a parameter's contents,
or a local that only aliases one of them (`val list = history; list.add(x)`). A local declared
in the function is its own state, and so is an object it creates there: a constructor call,
`mutableListOf()` and the other collection factories, `StringBuilder()`, `Channel()`,
`MutableStateFlow()`, `CoroutineScope(…)`, the receiver of `buildString`, `buildList`,
`buildSet`, `buildMap`, `coroutineScope`, `supervisorScope`, `withContext` and `runBlocking`, and
the receiver or `it` of `apply`, `run`, `with`, `also` or `let` on such an object. Anything is
fine on those.

On state it does not own, the rule reports:

- **A read-modify-write.** `x++`, `--x`, `x += …` and the other compound assignments, and an
  assignment whose value applies an accumulating operator (`+`, `-`, `*`, `/`, `%`, `inc`,
  `dec`, `!`, unary `-`, `inv`, `shl`, `shr`, `ushr`, `xor`, a string template) to the target's
  own previous value, read on the same receiver: `count = count + 1`, `point.x++`,
  `scores[0] += 1`, `scores[0] = scores[0] + 1`. Operators that settle are fine: `or`, `and`,
  `maxOf`, an elvis (`cache = cache ?: load()`), and `+` or `-` on a `Set` or a `Map`
  (`tags = tags + "seen"`).
- **A list that grows or loses another element.** `add`, `add(index, …)`, `addAll`, `+=`,
  `addFirst`, `addLast`, `removeAt`, `removeFirst`, `removeLast` on a receiver whose type is a
  `List` (a `MutableList`, an `ArrayList`, an `ArrayDeque`). The same calls on a `Set`, and
  `Map.put`, `set`, `remove` and `clear`, are idempotent and pass.
- **A call from the built-in table:** `StringBuilder.append`, `appendLine` and `insert`;
  `SendChannel.send`, `trySend` and `trySendBlocking`; `MutableSharedFlow.emit` and `tryEmit`
  (not on a `MutableStateFlow`, which drops an equal value); `launch`, `async` and `launchIn`;
  the atomic increments and adds of `java.util.concurrent.atomic`, `kotlin.concurrent.atomics`
  and `kotlinx.atomicfu` (`incrementAndGet`, `getAndAdd`, `fetchAndIncrement`, `+=`, …). The
  functions under `deny` are added to the table.
- **A varying value stored.** A value from `Random`, `System.currentTimeMillis`, `nanoTime`,
  `Clock.System.now` (`kotlin.time` or `kotlinx.datetime`), `TimeSource.Monotonic.markNow`,
  `java.time.*.now`, `UUID.randomUUID` or `Uuid.random`, directly or through a local, assigned
  to state the function does not own or put into it with `set`, `put`, `add`, `emit`, `send`
  and their kin. Reading the clock to compare, or keeping a UUID in a local, is fine.

A lambda counts as part of the function only when it runs within the call: one passed to an
inline function's inline parameter (`forEach`, `let`, `synchronized`), or to `withContext`,
`coroutineScope`, `supervisorScope`, `withTimeout` or `runBlocking`. A listener the function
registers, or the body of a launched coroutine, runs later and is not read; an anonymous object
or a local class is not read either. The bodies of local functions are read.

Calls to anything else pass: a function the rule knows nothing about is assumed to keep the
promise, so a finding is never a guess about code the rule cannot see.

## Settings

```yaml
rules:
  idempotent:
    allow: [com.acme.Audit.record, "*.Cache.put"]
    deny: [com.acme.Mailer.send, com.acme.charge]
    strict: false
```

- `allow`: globs over fully qualified functions whose call is idempotent. They win over the
  built-in table and over `deny`, and count as known under `strict`.
- `deny`: globs over fully qualified functions whose call is not idempotent, reported like the
  built-in table (a call on an object the function created excepted).
- `strict`: also report, as `KOTRAIL_CALLEE_NOT_KNOWN_IDEMPOTENT`, every call to a function that
  is neither annotated nor known: known are the standard library (`kotlin.*`, except
  `kotlin.io` and invoking a function value), `java.lang.String`, `java.lang.Math`,
  `java.util.Objects`, the functions in `allow`, an operation on a collection or a map, a call
  on an object the function created, a local function and a constructor. Off by default: it
  asks every callee of an annotated function to be annotated in turn, which suits a module
  built around idempotent operations more than one adopting the annotation function by function.
- `annotations`: annotations that mark a function or a setter as idempotent; replaces the
  default list.

## When it stays quiet

- The function or setter does not carry one of `annotations`.
- The function has no body (an interface member, an `expect` declaration).
- Every write sets a value that does not depend on the previous one, or goes to a local or an
  object the function created.

What it does not see: a write through a function the rule knows nothing about (outside
`strict`), a write inside a lambda stored and invoked later, and an alias created by
reassigning a local after its declaration. An `@Idempotent` override is checked; an override of
an `@Idempotent` member is checked only when it carries the annotation itself.

## Fixtures

`compiler-tests/testData/diagnostics/idempotent.kt` (read-modify-write, setters, captured
variables), `idempotentCalls.kt` (the built-in table and the idempotent collection operations),
`idempotentVaryingValues.kt` (time, random and UUID values stored and read),
`config/idempotentAllowDeny.kt`, `config/idempotentStrict.kt`

## Implementation notes

`fir/checkers/IdempotentChecker.kt`, a `FirBasicDeclarationChecker` over named functions and
property setters. A visitor walks the body in order, collecting the locals it declares, which of
them hold an object the function created, and which hold a varying value; lambdas are entered
only when the call runs them in place. A variable assignment is a read-modify-write when its
source is a desugared increment or decrement, or when its value applies an accumulating
operator to a read of the same property on the same receiver; an indexed one is a `set` call
whose value does so to a `get` on the same receiver. Calls are matched by fully qualified name,
fake overrides unwrapped, except the list operations, which are matched by name on a receiver
whose type is a subtype of `kotlin.collections.List`.

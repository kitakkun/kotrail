# Unretained

**Diagnostic:** `KOTRAIL_UNRETAINED_PARAMETER_RETAINED` (error, on the expression through which the parameter is retained)
**Key:** `rules.unretained` (on by default; inert until a parameter is annotated)
**Settings:** `annotations` (default `[com.kitakkun.kotrail.lifetime.Unretained]`), `weakTypes` (default `[java.lang.ref.WeakReference, java.lang.ref.SoftReference, kotlin.native.ref.WeakReference]`)

## What it is for

Whether a reference should be weak is a matter of intended lifetime, which no analysis can read
off the code: a coroutine inspector that stores every registered `Job` strongly, to be removed
on completion, pins a scope that was abandoned without being cancelled, together with
everything its coroutines captured, and so creates the very leak it exists to reveal. Nothing
in the types says the `Job` may never complete. Once the intent is written on the parameter,
keeping to it is decidable:

```kotlin
import com.kitakkun.kotrail.lifetime.Unretained

fun register(@Unretained job: Job, name: String)
```

The annotation is a contract: the function may read the parameter, call it and register
callbacks on it, but must not keep it alive past the call except through a weak reference. It
lives in the `kotrail-annotations` artifact, which the Gradle plugin adds as `compileOnly`; a
project's own annotation is listed under `annotations`.

## What it rejects

```kotlin
fun register(@Unretained job: Job, name: String) {
    roots[name] = job                              // reported: stored in a property
    jobs.add(job)                                  // reported: passed to 'MutableList.add', which does not declare its parameter unretained
    scope.launch { track(job) }                    // reported: captured by a lambda that outlives the call
    val handle = job
    current = handle                               // reported: an alias escapes the same way
}

fun handle(@Unretained job: Job): Job = job        // reported: returned

class Owner(@Unretained val job: Job)              // reported: a constructor property retains it from the start
```

## What it asks for

```kotlin
fun register(@Unretained job: Job, name: String) {
    roots[name] = WeakReference(job)               // kept weakly
    job.invokeOnCompletion { roots.remove(name) }  // a callback registered on the parameter itself
    job.start()                                    // used
    watch(job)                                     // passed on under the same contract: fun watch(@Unretained job: Job)
}
```

## What counts as an escape

The parameter and its aliases (locals assigned from it, the receiver or `it` of `also`, `apply`,
`let`, `run` and `with`) escape when one of them is:

- stored in a non-local property, or is a `val`/`var` constructor parameter;
- passed as an argument to a callee whose corresponding parameter is not annotated, unless the
  callee constructs one of `weakTypes` or is one of a few pure functions (`equals`, `hashCode`,
  `toString`, `require`, `check`, `error`, `println`);
- read inside a lambda that the call does not inline: a `launch { }` body, a listener, a
  stored callback. A lambda passed to an inline function's inline parameter, or handed to the
  parameter itself (`job.invokeOnCompletion { }`), runs within the call and is not an escape;
- returned from the function.

Calling a method on the parameter is never an escape.

## Across modules

The annotation has binary retention, so a library's contract is visible to its callers: passing
an `@Unretained` value to a library function whose parameter is also `@Unretained` is fine, and
passing it to one that is not is reported. A callee the analysis cannot see counts as retaining,
which makes a finding a possible false positive but never a false negative; mark the callee's
parameter, or wrap the value weakly, to settle it.

## When it stays quiet

- No parameter of the function carries one of `annotations`.
- Every use of the parameter is a method call on it, a callback registered on it, a weak
  reference, or a hand-off to a callee that declares its parameter unretained.
- The function has no body (an interface member, an `expect` declaration).

## Fixtures

`compiler-tests/testData/diagnostics/unretained.kt`

## Implementation notes

`fir/checkers/UnretainedChecker.kt`, a `FirBasicDeclarationChecker` over named functions and
constructors. For each annotated parameter a visitor walks the body with a set of alias
symbols, growing it through local initializers, assignments to locals and the scope functions'
receivers, and records an escape at each retaining site. Lambdas are looked into only when the
callee inlines them (`isInline` and neither `crossinline` nor `noinline`) or when the receiver is
the parameter; otherwise the first read of an alias inside them is the finding.

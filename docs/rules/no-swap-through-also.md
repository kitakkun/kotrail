# No swap through also

**Diagnostic:** `KOTRAIL_SWAP_THROUGH_ALSO` (error, on the function's name)
**Key:** `rules.noSwapThroughAlso` (on by default)
**Settings:** `functions` (default: `kotlin.also`, `kotlin.apply`, `kotlin.let`, `kotlin.run`)
**Fix:** automatic for `also` and `apply` as a local's initializer, a `return`'s operand, or a lambda's last expression (see [Fix](#fix))

## What it rejects

The get-and-set idiom written through a scope function: the expression yields the variable's
old value while its block assigns the new one.

```kotlin
val replaced = synchronized(lock) {
    ready.also {              // reported: returns the previous ready while the block overwrites ready
        ready = frame
        showingKeptFrame = false
        frameCounter++
    }
}
replaced?.close()
```

A reader sees `ready ... ready = frame` and misses that the old value is what comes out. Dropping
the `.also` then looks harmless, and it loses the old value: here a native bitmap that must be
closed leaks once per frame.

## What it asks for

Name the previous value and assign on a line of its own:

```kotlin
val replaced = synchronized(lock) {
    val previous = ready
    ready = frame
    showingKeptFrame = false
    frameCounter++
    previous
}
replaced?.close()
```

The order is now on the page: the old value is read, then overwritten, then handed out. The
readable form costs one line.

`value.also { value = new }` is a known Kotlin idiom, and this rule is opinionated about it. It
ships on because the idiom hides exactly the fact a reader needs (which value comes out) and the
spelled-out form is one line longer.

## When it fires

- The call is to a function in `functions` (the receiver forms of `also`, `apply`, `let` and
  `run` by default), on an explicit receiver, with a lambda that runs in place (an inline lambda,
  or one the callee's contract calls in place).
- The receiver is a read of a variable: a local `var`, a top-level property, or a property of
  `this`, written (`this.ready`) or implicit (`ready`); a smart cast of it and a safe call
  (`ready?.also { ready = null }`) count too.
- The lambda assigns that same variable: `x = ...`, `this.x = ...`, `x += ...`, `x++`, at any
  depth that runs before the call returns: the block itself and inline lambdas inside it
  (`ready.also { repeat(1) { ready = frame } }`).
- Whether the block reads `it` or `this` does not matter: `ready.also { it?.close(); ready = null }`
  is reported, and reading the old value next to the assignment only makes it harder to follow.
- The message says what comes out: `also` and `apply` return the previous value; `let` and `run`
  hand it to their block, which overwrites the variable it came from.

## When it stays quiet

- The block does not assign the receiver's variable: `result.also { log(it) }`,
  `ready.also { frameCounter++ }`, `x.also { listOf(x).forEach { y = it } }`.
- The assignment is in code that runs later, if at all: a lambda stored for later
  (`ready.also { onReset = { ready = null } }`), a local function, a local class or object.
- The receiver is not a variable read: a call, a literal, or a property read through another
  object. `holder.value.also { holder.value = next }` is out of scope: the rule keeps to the
  variables the code at hand owns, locals and properties of `this` or at top level, and leaves
  another object's state to that object (see [No external mutation](no-external-mutation.md)).
- A lambda that does not run in place: a function in `functions` that is neither inline nor
  promises by contract to call the lambda before it returns.
- Files in a test source set (`src/test/`, `src/jvmTest/`, `src/androidUnitTest/` and kin), and
  generated code, by the project's `generated` settings, like every rule.

## Fix

`kotrailFix` reads the variable first and lets the block's statements follow on their own lines,
for a function that returns its receiver (`also`, `apply`) in three places:

| Written | Becomes |
|---|---|
| `val replaced = ready.also { ... }` | `val replaced = ready`, then the statements |
| `return ready.also { ... }` | `val previous = ready`, the statements, `return previous` |
| `ready.also { ... }` as the last expression of a lambda whose value is used | `val previous = ready`, the statements, `previous` |

The fix is recorded only when moving the statements out of the lambda keeps their meaning: the
lambda reads neither `it` nor its receiver (written or implicit) and has no explicit parameter
list, does not `return@also`, declares nothing at its top level, and contains no raw string; the
call is written `ready.also { ... }` (no safe call, label or parentheses) with the block on one
line or its braces on lines of their own; the statement has its line to itself; and the name
`previous` appears nowhere in the enclosing function. Everything else, `let` and `run` included,
is reported without a fix.

## Settings

```yaml
rules:
  noSwapThroughAlso:
    functions: [kotlin.also, kotlin.apply, kotlin.let, kotlin.run, com.acme.util.alsoLocked]
```

`functions` replaces the default list. A function counts when it is called on a receiver with a
lambda that runs in place; the message says `returns` for a function declared `T.f(...): T` and
`hands its block` otherwise, and only the former gets the fix.

## Related rules

[Named condition](named-condition.md) and [Prefer idiom](prefer-idiom.md) work on the same
question from other sides: whether an expression says what it decides in a form a reader takes
in at once. [Must close](must-close.md) catches a resource that is created and never closed; this
rule catches the shape in which the old one is easiest to lose.

## Fixtures

`compiler-tests/testData/diagnostics/noSwapThroughAlso.kt`; the fix is exercised by the
`kotrailFix` functional test in `gradle-plugin`.

## Implementation notes

`fir/checkers/NoSwapThroughAlsoChecker.kt`, a `FirFunctionCallChecker`. A variable is identified
by its property symbol and the owners of the `this` receivers it is read on, so `ready` and
`this.ready` are the same variable while `other.ready` is none. The block is walked with a
visitor that enters an anonymous function only when it runs in place (`inlineStatus` is
`Inline`, or its `invocationKind` is set by a contract) and never enters a local function or
class. The fix finds its place from the checker's containing elements (a local property, an
explicit `return`, or the implicit return of a lambda's last statement) and rebuilds the text from
the receiver and the lambda's source, re-indented at the statement's indentation.

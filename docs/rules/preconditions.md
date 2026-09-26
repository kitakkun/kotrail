# Preconditions

**Diagnostic:** `KOTRAIL_PRECONDITION_VIOLATED` (error, on the call)
**Key:** `rules.preconditions` (on by default)
**Artifact:** `annotations` (`com.kitakkun.kotrail.preconditions.InferredPreconditions`, written by the plugin)

## What it rejects

```kotlin
fun retry(times: Int) {
    require(times >= 0)
    ...
}

retry(-1)                              // reported: retry requires times >= 0 (times = -1)

val attempts = BASE * 2 - MAX_RETRIES  // const vals; folds to -1
retry(attempts)                        // reported through the local

Percent(150)                           // reported: the init block requires value in 0..100
```

## What it asks for

Arguments that satisfy the callee's own `require` / `check` / `requireNotNull` calls. The
contract is already written; this rule reads it and applies it at every call site whose
arguments are known at compile time, in this module and in every module that depends on it.

A `require` that fails is a crash that arrives at runtime, usually in a path the tests did not
reach. A generated call with a plausible-looking constant (`retries = -1`, `alpha = 1.5f`,
`substring(5, 2)`) is the typical way that happens.

## How contracts are found

At the top of a function body, and at the top of each `init` block of a class (for its primary
constructor), the leading run of these calls is read:

| Call | Contract |
| --- | --- |
| `require(condition)` / `check(condition)` | `condition` |
| `requireNotNull(x)` / `checkNotNull(x)` | `x != null` |

Scanning stops at the first statement that is not one of these, so a `require` after other
work is not a precondition and is ignored. A condition is recorded only when it mentions nothing
but parameters and constants, in this language:

- comparisons (`<`, `<=`, `>`, `>=`), equality (`==`, `!=`, including `!= null`)
- `&&`, `||`, `!`
- `+`, `-`, `*`, `/`, `%`, unary minus, on numbers (and `+` on strings)
- `in` / `!in` with `..`, `..<`, `until`
- `String.length`, `isEmpty()`, `isNotEmpty()`, `isBlank()`, `isNotBlank()`; `Collection.size`, `isEmpty()`, `isNotEmpty()`
- a `const val` or a final `val` with a constant initializer, folded into the condition

Anything else (a property of `this`, a call to another function, a `var`) makes the condition
unrecordable. It is then simply not verified; it never causes a false report.

For classes, the parameters are the primary constructor's parameters and the `val` properties
declared in it. `var` properties are excluded because an earlier `init` block could reassign them.

## How arguments are folded

An argument counts as known when it can be reduced to a constant without executing anything:

- literals, string templates of known parts
- `const val`, a final `val` with a constant initializer and no custom getter, a local `val`
- arithmetic on known values, `toInt()` / `toLong()` / `toFloat()` / `toDouble()`
- `if (c) a else b` and `a ?: b` when their inputs are known
- `listOf(...)` / `emptyList()` of known elements

Parameters left to their defaults use the default expression, which may itself refer to earlier
parameters, when the callee is compiled in the same module.

A condition is reported only when it evaluates to `false` with every input known, or when an
`&&` / `||` is decided by its known side. An argument that is a parameter of the enclosing
function, a `var`, or the result of a call is unknown, and a condition that depends on it is
never reported.

## Across modules

When a module is compiled, every non-private function and class with recordable preconditions
receives an `@InferredPreconditions("times >= 0", ...)` annotation in its metadata. Dependent
modules read it and check their calls the same way. The annotation can also be written by hand
on a declaration whose body is not visible, and then takes precedence over analysis.

## When it stays quiet

- No argument of the call is known, or the condition depends on an unknown one.
- The callee has no leading `require` / `check`, or its conditions are not recordable.
- The callee is a secondary constructor (only the primary constructor is covered).
- The call is compiler-generated.

## Fixtures

`compiler-tests/testData/diagnostics/preconditions.kt`,
`compiler-tests/testData/box/preconditions/inferredMetadataAcrossModules.kt`

## Implementation notes

`preconditions/` holds the compiler-independent core: `Value`, the `Cond` tree with its
three-valued evaluator and renderer, and `CondParser`. `fir/preconditions/CondConverter.kt`
turns resolved FIR into a `Cond`, following `val`s to their initializers; the same converter
serves extraction (with the callee's parameters mapped to `Param` nodes) and argument folding
(with no parameters, so every reference must fold). `PreconditionService.kt` holds two inferred
facts, one per function and one per class, on the shared base described in
[Inferred metadata](../inferred-metadata.md): the annotation, hand-written or inferred, wins over
the body; the shared warm-up fills the cache while bodies exist, and the shared writer puts
`@InferredPreconditions` into the metadata.

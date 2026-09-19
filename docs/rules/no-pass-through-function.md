# No pass-through function

**Diagnostic:** `KOTRAIL_PASS_THROUGH_FUNCTION` (error, on the function name)
**Key:** `rules.noPassThroughFunction` (on by default)
**Settings:** none

## What it rejects

A function that is another function under a different name: its whole body is one call with the
same shape as its own signature, receiving its parameters unchanged and in order, and returning
what the call returns.

```kotlin
fun persist(user: User, force: Boolean): Boolean = store(user, force)
fun String.loud(): String = uppercase()
fun hello(vararg names: String): String = greet(*names)
```

## What it asks for

Call the target directly. A caller can replace `persist(user, force)` with `store(user, force)`
argument for argument, and nothing changes; the function only costs every reader a jump to learn
that. An assistant adding "one more layer" is how these accumulate.

## What it deliberately does not report

The rule reports only the unambiguous shape. Everything that could be a decision is left alone,
even when the body is a single forwarding call:

```kotlin
class UserService(private val repository: Repository) {
    fun saveUser(user: User) = repository.save(user)   // encapsulation: callers cannot reach repository
}
fun String.size(): Int = parseImpl(this)               // a different call shape (receiver -> argument)
fun stash(force: Boolean, user: User) = store(user, force)   // reordered: an adapter
fun Caption(text: String) = Text(text)                 // Text has more parameters: a narrower API
fun connect(host: String) = connect(host, DEFAULT_PORT) // supplies a default
fun items(): List<Item> = mutableItems()               // narrows the type
fun user(name: String): User = User(name)              // a factory over a constructor
fun parse(text: String): Json = JsonParser.parse(text) // public over a non-public callee: a facade
fun dispatchStart(name: String) = onStart(name)        // public bridge to a protected member: a facade
```

Also left alone: overrides, `operator`, `inline`, `actual`, `external` and local functions;
generic functions and generic callees (a specialization); a change of `suspend`; functions with a
`kotlin.jvm` annotation (Java-facing adapters); a callee reached through operator syntax
(`block()`, `a + b`); and `@Preview` composables, which exist to call what they preview and which
[preview-required](compose/preview-required.md) asks for.

## When it fires

All of the following hold:

- the body is one call, written as an expression body, a `return`, or a single statement;
- the callee has no explicit receiver, or `this`; an extension's receiver is the callee's receiver;
- every argument is the function's own parameter at the same position (a `vararg` may be spread
  through), every parameter is passed, and every parameter of the callee receives one;
- each parameter has the same type as the callee's parameter, and the function's return type is
  the call's type;
- the callee is a function other than the function itself, with the same `suspend`-ness, and
  no less visible than the function: a `public` function over a `private`, `internal`, or
  `protected` callee is a facade, since its callers could not call the callee themselves.

A facade over a callee of the *same* visibility is reported, since the two cannot be told apart
from the outside; that is what `@Suppress("KOTRAIL_PASS_THROUGH_FUNCTION")` is for.

## Relation to no-pass-through-return

[No pass-through return](no-pass-through-return.md) reports a function that returns one of its
inputs unchanged. This rule reports a function that hands everything to another function
unchanged. They are siblings: one finds a function that computes nothing, the other a function
that delegates everything.

## Fixtures

`compiler-tests/testData/diagnostics/noPassThroughFunction.kt`

## Implementation notes

`fir/checkers/PassThroughFunctionChecker.kt`. A `FirSimpleFunctionChecker` that takes the body's
single statement (unwrapping a `return`), checks the call's receiver shape against the function's
own, and walks `resolvedArgumentMapping` in order, requiring each argument to resolve to the
function's parameter at that position. Types are compared with `AbstractTypeChecker.equalTypes`,
and visibilities with `EffectiveVisibility.relation`, so that `protected` (part of the public
API, but unreachable from outside the hierarchy) counts as less visible than `public`.

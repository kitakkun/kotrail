# No pass-through function

**Diagnostic:** `PASS_THROUGH_FUNCTION` (error, on the function name)
**Switch:** `rules.noPassThroughFunction` (default `true`)
**Severity key:** `severity.noPassThroughFunction`
**Settings:** none

## What it rejects

A function whose whole body is one call that receives the function's own parameters unchanged
and returns what the call returns:

```kotlin
fun saveUser(user: User): Boolean = repository.save(user)
fun String.loud(): String = uppercase()
fun hello(vararg names: String): String = greet(*names)
```

## What it asks for

Call the target directly, or make the wrapper do something. Each of these is a function with a
reason to exist, and none is reported:

```kotlin
fun connect(host: String) = connect(host, DEFAULT_PORT)   // supplies a default
fun items(): List<Item> = mutableItems()                  // narrows the type
fun parse(text: String): Json = JsonParser.parse(text)    // public over a private callee: a facade
fun user(name: String): User = User(name)                 // a factory over a constructor
```

A layer with nothing in it costs every reader a jump to learn that it does nothing, and it is
how "one more layer" accumulates when an assistant adds a service method for every repository
method.

## When it fires

All of the following hold:

- the body is one call, written as an expression body, a `return`, or a single statement;
- every argument is one of the function's own parameters (a `vararg` may be spread through),
  each passed exactly once, and every parameter is passed;
- the callee is reached through nothing, `this`, a property of `this`, or one of the parameters;
  the function's extension receiver, if it has one, is passed on as the callee's receiver or as
  an argument;
- each parameter has the same type as the callee's parameter, and the function's return type is
  the call's type;
- the callee is a function other than the function itself.

## When it stays quiet

- Overrides, `operator`, `inline`, `actual`, `external` and local functions: forwarding is their
  purpose or their shape.
- Functions with a `kotlin.jvm` annotation (`@JvmStatic`, `@JvmName`, `@JvmOverloads`): adapters
  for Java callers.
- A callee that is a constructor: a factory function keeps the option of changing how instances
  are made.
- A callee reached through operator syntax (`block()`, `a + b`): the wrapper gives syntax a name,
  which is a decision.
- A public function whose callee is not public: a facade that hides the implementation.
- Any parameter with a default value, any argument the wrapper supplies itself, any conversion of
  a type, any receiver obtained by a call.

A public facade over a callee of the *same* visibility is reported, since the two cannot be told
apart from the outside; that is what `@Suppress("PASS_THROUGH_FUNCTION")` is for.

## Relation to no-pass-through-return

[No pass-through return](no-pass-through-return.md) reports a function that returns one of its
inputs unchanged. This rule reports a function that hands everything to another function
unchanged. They are siblings: one finds a function that computes nothing, the other a function
that delegates everything.

## Fixtures

`compiler-tests/testData/diagnostics/noPassThroughFunction.kt`

## Implementation notes

`fir/checkers/PassThroughFunctionChecker.kt`. A `FirSimpleFunctionChecker` that takes the body's
single statement (unwrapping a `return`), reads the call's `resolvedArgumentMapping`, and checks
each argument resolves to a distinct parameter of the function. Types are compared with
`AbstractTypeChecker.equalTypes`, so a wrapper over a generic callee with a concrete type is a
specialization and is left alone.

# Implicit receivers

**Diagnostics:** `KOTRAIL_IMPLICIT_RECEIVER_AMBIGUOUS` (error, on the access), `KOTRAIL_TOO_MANY_IMPLICIT_RECEIVERS` (error, on the lambda)
**Key:** `rules.implicitReceivers` (on by default)
**Settings:** `qualifyAmbiguous` (default `true`), `maxDepth` (default `0`, disabled)

## What it rejects

A bare name that two implicit receivers in scope could supply:

```kotlin
class Screen(val view: View) {
    var text: String = ""

    fun bind() {
        view.apply {
            text = "hello"        // reported: View.text wins, but Screen has a text too
        }
    }
}
```

and, when `maxDepth` is set, the lambda that puts more implicit receivers in scope than the
limit allows:

```kotlin
view.apply {
    text = buildString { append("b") }   // reported at maxDepth: 2 (Screen, View, StringBuilder)
}
```

## What it asks for

```kotlin
view.apply {
    this.text = "hello"          // View's
    this@Screen.text = "hello"   // Screen's
}
```

The compiler picks the nearest receiver that has the member, every time and without a word. A
reader who has the other receiver in mind reads the line wrong, and an assignment goes to the
wrong object with no diagnostic to say so; this is the classic `apply` mistake. Qualifying the
access settles which one is meant.

A name that only one receiver has is not reported, however far out that receiver is. A class
member used inside `runBlocking { }`, `launch { }`, `apply { }`, `buildJsonObject { }`, or a
mocking DSL is the ordinary way to write Kotlin, and `this@Owner.` on each such name would make
the code worse, not clearer.

## How receivers are counted

An implicit receiver is put in scope by:

- an enclosing class or object (its dispatch receiver); an `inner` class keeps the outer one, a
  nested class does not;
- an enclosing extension function or property (its extension receiver), a member extension
  putting two in scope at once;
- an enclosing lambda with a receiver: `apply`, `run`, `with`, `buildString`, a DSL block.

Lambdas without a receiver (`forEach`, `let`, `map`) and local functions add nothing.

`qualifyAmbiguous` looks at every call and property access whose receiver was supplied
implicitly and reports it when another receiver in scope declares or inherits a function or
property of the same name. Extensions in scope are not consulted: only members count. The message
names the receiver the access resolved on and the other one, as `this@Screen`, `this@show`
(an extension function's receiver), or `this@apply` (a lambda's implicit label).

`maxDepth` counts the receivers in scope at a lambda, including its own, out to the nearest
non-inner class, and reports the lambda that exceeds the limit. It is off by default: every
method that uses `apply` already has two, and where the line goes is a project's decision.

```yaml
rules:
  implicitReceivers:
    maxDepth: 2
```

## When it stays quiet

- The access is qualified (`this.text`, `this@Screen.text`, `screen.text`).
- Only one receiver in scope has a member of that name.
- The other receiver has the same type as the one that wins (`Row { Row { } }`, a JSON builder
  nested in a JSON builder): the nearest one is what everybody means.
- The callee is a member extension, which takes both receivers as one declaration.
- The name is `toString`, `hashCode`, or `equals`: every receiver has these.
- Only one receiver is in scope: a plain method, a top-level extension, a lambda in a top-level
  function.
- `maxDepth` is `0`, or the count is within it.

## Fixtures

`compiler-tests/testData/diagnostics/implicitReceivers.kt`,
`compiler-tests/testData/diagnostics/config/implicitReceiversDepth.kt`

## Implementation notes

`fir/checkers/ImplicitReceiverChecker.kt`: `AmbiguousImplicitReceiverChecker`, a
`FirQualifiedAccessExpressionChecker`, takes the `dispatchReceiver` and `extensionReceiver` of the
access that are `FirThisReceiverExpression`s with `isImplicit`, reads the `boundSymbol` of their
`FirThisReference`, and asks every other receiver the enclosing declarations introduce (a
`FirClassSymbol`, or a callable's `receiverParameterSymbol`, resolved to its class) whether its
`unsubstitutedScope` has a function or property of the callee's name.
`ImplicitReceiverDepthChecker`, a `FirAnonymousFunctionChecker`, sums those receivers over the
enclosing declarations up to the first non-inner class.

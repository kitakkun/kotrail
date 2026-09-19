# Implicit receivers

**Diagnostics:** `KOTRAIL_IMPLICIT_RECEIVER_FROM_OUTER_SCOPE` (error, on the access), `KOTRAIL_TOO_MANY_IMPLICIT_RECEIVERS` (error, on the lambda)
**Key:** `rules.implicitReceivers` (on by default)
**Settings:** `qualifyOuter` (default `true`), `maxDepth` (default `0`, disabled)

## What it rejects

A bare name that resolves on an outer implicit receiver while a nearer one is in scope:

```kotlin
class Screen(val view: View) {
    fun title(): String = "t"

    fun bind() {
        view.apply {
            text = title()        // reported: title() is Screen's; View is the nearest this
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
    text = this@Screen.title()
}
```

With two receivers in scope, a bare `title()` reads as the nearer one's until the reader goes
and checks which class declares it. The compiler resolves it correctly every time; the reader
does not. Naming the receiver (`this@Screen.title()`, or a local `val screen = this` before the
block) says where the name belongs, and is what an IDE inlay hint would have shown.

## How receivers are counted

An implicit receiver is put in scope by:

- an enclosing class or object (its dispatch receiver); an `inner` class keeps the outer one, a
  nested class does not;
- an enclosing extension function or property (its extension receiver), a member extension
  putting two in scope at once;
- an enclosing lambda with a receiver: `apply`, `run`, `with`, `buildString`, a DSL block.

Lambdas without a receiver (`forEach`, `let`, `map`) and local functions add nothing.

`qualifyOuter` looks at every call and property access whose receiver was supplied implicitly,
and reports it when that receiver is not one of the innermost declaration's: a class member used
inside `apply`, a member used from an `inner` class, the class's own member used inside a member
extension. A receiver marked with `@DslMarker` already forbids the outer access at the language
level, so nothing inside such a block is reported unless it resolved anyway (an outer class
member is still reachable from a DSL block, and is reported).

`maxDepth` counts the receivers in scope at a lambda, including its own, out to the nearest
non-inner class, and reports the lambda that exceeds the limit. It is off by default: every
method that uses `apply` already has two, and where the line goes is a project's decision.

```yaml
rules:
  implicitReceivers:
    maxDepth: 2
```

## When it stays quiet

- The access is qualified (`this@Screen.title()`, `this.invalidate()`, `screen.title()`).
- The name resolves on the innermost receiver.
- Only one receiver is in scope: a plain method, a top-level extension, a lambda in a top-level
  function.
- `maxDepth` is `0`, or the count is within it.

## Fixtures

`compiler-tests/testData/diagnostics/implicitReceivers.kt`,
`compiler-tests/testData/diagnostics/config/implicitReceiversDepth.kt`

## Implementation notes

`fir/checkers/ImplicitReceiverChecker.kt`: `OuterImplicitReceiverChecker`, a
`FirQualifiedAccessExpressionChecker`, takes the `dispatchReceiver` and `extensionReceiver` of the
access that are `FirThisReceiverExpression`s with `isImplicit`, reads the `boundSymbol` of their
`FirThisReference`, and compares it with the receivers the innermost declaration in
`CheckerContext.containingDeclarations` introduces (a `FirClassSymbol`, or a callable's
`receiverParameterSymbol`). `ImplicitReceiverDepthChecker`, a `FirAnonymousFunctionChecker`, sums
those receivers over the enclosing declarations up to the first non-inner class.

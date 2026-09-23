# Narrative order

**Diagnostic:** `KOTRAIL_HELPER_BEFORE_FIRST_USE` (error, on the function name)
**Key:** `rules.narrativeOrder` (on by default)
**Settings:** none
**Fix:** automatic (`kotrailFix` moves the function to just after its first caller)

## What it rejects

A private function declared before the first declaration that uses it:

```kotlin
private fun parseHeader(bytes: ByteArray): Header { ... }     // reported

fun load(bytes: ByteArray): Document {
    val header = parseHeader(bytes)
    ...
}
```

## What it asks for

```kotlin
fun load(bytes: ByteArray): Document {
    val header = parseHeader(bytes)
    ...
}

private fun parseHeader(bytes: ByteArray): Header { ... }
```

Read from the top, a file should tell the story first and the details after, the way a newspaper
article does. Helpers placed above their callers make the reader hold every detail before
learning what it is for; helpers placed below let the reader stop once the story is clear. A
private function's callers are all in the same class or file, so the order is decidable.

## When it fires

- The function is `private` and has a body.
- Some other declaration of the same container (a function, a property initializer or accessor,
  an `init` block) calls it or takes a reference to it, and the earliest such declaration comes
  after the function.

## When it stays quiet

- Nothing in the container uses the function, or the function is not private (a public
  function may be called from anywhere, and its order is a different question).
- The function and its first user use each other (mutual recursion): neither order tells the
  story.
- Nested classes are containers of their own; a helper used only from a nested class is ordered
  within that class.

## Fixtures

`compiler-tests/testData/diagnostics/narrativeOrder.kt`

## Implementation notes

`fir/checkers/NarrativeOrderChecker.kt`, a `FirRegularClassChecker` and a `FirFileChecker` over
the container's direct declarations. A `FirVisitorVoid` collects the named functions each
declaration calls (`FirFunctionCall`) or references (`FirCallableReferenceAccess`); a private
function is reported when the earliest declaration using it starts after it. The fix deletes the
function's lines (and one following blank line) and inserts the text after the first user with a
blank line and the user's indentation.

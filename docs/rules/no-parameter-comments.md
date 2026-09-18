# No parameter comments

**Diagnostic:** `KOTRAIL_COMMENT_IN_PARAMETER_LIST` (error, on the comment)
**Switch:** `rules.noParameterComments` (default `true`)
**Severity key:** `severity.noParameterComments`
**Settings:** none

## What it rejects

A comment inside the parameter list of a function or constructor, whatever its spelling:

```kotlin
fun connect(
    host: String,
    // how many times to retry before giving up
    retries: Int,
    timeoutMillis: Long, /* per attempt */
    /** Whether to fall back to plain HTTP. */
    allowInsecure: Boolean,
)

class Client(
    val host: String,
    // milliseconds
    val timeout: Long,
)
```

## What it asks for

```kotlin
/**
 * Opens a connection to [host].
 *
 * @param retries how many times to retry before giving up
 * @param timeoutMillis the budget per attempt
 * @param allowInsecure whether to fall back to plain HTTP
 */
fun connect(host: String, retries: Int, timeoutMillis: Long, allowInsecure: Boolean)
```

or a comment above the declaration when it is about the function rather than one parameter.

A comment between parameters is shown nowhere the parameter is used: the IDE's signature help,
quick documentation, and generated API docs all read KDoc. It is also loosely attached. It sits
next to a parameter by position only, so reordering, removing, or inserting a parameter leaves it
describing the wrong one, which is exactly the edit an assistant makes without noticing the
comment. `@param` is bound to the name and moves with it.

## When it fires

A comment (`//`, `/* */`, or `/** */`) whose whole range lies inside the parentheses of a
parameter list of a declared function, a primary or secondary constructor, or an anonymous
function written with `fun`. Each comment is reported once, on its own range.

## When it stays quiet

- Comments above the declaration, in the body, or trailing a statement.
- KDoc on the declaration itself.
- Argument lists at call sites (`f(/* retries */ 3)`); a name is the answer there, and
  [named arguments for repeated types](named-arguments-for-repeated-types.md) covers the case
  where it matters most.
- Lambda parameters (`{ /* n */ n -> ... }`).
- An empty parameter list (`fun f(/* nothing */)`): the parentheses are located from the
  parameters' own source ranges, so a list with none is not examined.
- Comment-looking text inside string literals.

## Fixtures

`compiler-tests/testData/diagnostics/noParameterComments.kt`

## Implementation notes

`fir/checkers/ParameterCommentChecker.kt`. A `FirFileChecker`: comments are not part of FIR, so
the file text is scanned with the same `CommentScanner` the comment-length rule uses, and each
comment is matched against the parameter lists of the file's functions, collected with a FIR
visitor: from the `(` before the first parameter's source to the `)` after the last, stepping
over whitespace, commas, and comments. The plugin stays clear of the platform's syntax-tree
classes, whose relocated names differ between the embeddable compiler and the test framework.
The report lands on the comment through `CommentRangeAnchor`, shared with the comment-length rule.

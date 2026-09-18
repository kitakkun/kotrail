# Function length

**Diagnostic:** `KOTRAIL_FUNCTION_TOO_LONG` (error, on the function name)
**Key:** `rules.functionLength` (on by default)
**Settings:** `maxLines` (default `50`), `maxComposableLines` (default `80`); `0` for unlimited

## What it rejects

A function whose body has more lines of code than the limit:

```kotlin
fun sync() {
    // 72 lines of code
}
```

```
[Kotrail] This function is 72 lines of code (limit 50). Split it so that each piece does one
thing and has a name.
```

## What it asks for

Extraction. A long function is where an assistant keeps adding "one more thing", because nothing
pushes back. Past the limit the only way to add is to pull a piece out, and the piece has to be
given a name, which is the point.

## What counts as a line

Only lines of code. These never count, so formatting and documentation cannot tip a function
over the limit:

- blank lines,
- lines holding nothing but `{` or `}`,
- comment lines: `//`, `/*`, and the `*` continuation lines of a block comment.

An expression body (`fun f() = ...`) is measured over the lines its expression spans. Lambdas and
local functions inside the body count toward it, as they are part of what the reader has to hold.

## Composables

A `@Composable` function gets `maxComposableLines` instead. A UI tree runs
longer than logic of the same complexity, and splitting a layout too finely hurts more than it
helps, so the default is higher.

## Tests

Test functions are measured like any other. A test that grows past the limit is usually several
tests, and the per-compilation configuration (see [configuration.md](../configuration.md#test-source-sets))
is the place to raise the limit for a test compilation if the project disagrees.

## When it stays quiet

- The limit that applies is `0`.
- The function has no body: `abstract`, `expect`, and interface members without a default.

## Fixtures

`compiler-tests/testData/diagnostics/functionLength.kt`

## Implementation notes

`fir/checkers/FunctionLengthChecker.kt`. A `FirSimpleFunctionChecker` that takes the body's source
range inside the function's own source text and counts the lines that pass the filter above. It
reads text rather than the FIR tree because "lines" is a property of the source, and because a
count of statements would reward dense one-liners.

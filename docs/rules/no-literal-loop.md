# No literal loop

**Diagnostic:** `KOTRAIL_LITERAL_LOOP` (error, on the looped expression)
**Key:** `rules.noLiteralLoop` (on by default)
**Settings:** `maxElements` (default `3`)

## What it rejects

A loop over a handful of literals whose body branches on the element, and a loop over data
with a literal sentinel joined in for the same purpose:

```kotlin
listOf(false, true).forEach { option ->                                      // reported
    FilterChip(text = if (option) "All devices" else "This device", selected = option == allDevices)
}
(listOf(null) + days).forEach { option ->                                    // reported
    FilterChip(text = option ?: "All dates", selected = option == day)
}
```

## What it asks for

The cases written out, since they were cases all along:

```kotlin
FilterChip(text = "This device", selected = !allDevices, onClick = { actions.showAllDevices(false) })
FilterChip(text = "All devices", selected = allDevices, onClick = { actions.showAllDevices(true) })

FilterChip(text = "All dates", selected = day == null, onClick = { actions.filterDay(null) })
days.forEach { FilterChip(text = it, selected = it == day, onClick = { actions.filterDay(it) }) }
```

A literal collection is not data the code receives; it is the author's own list of cases. A body
that branches on the element (`if (option)`, `option ?: "All"`, `when (option)`) is those cases
written once each after all, only folded into a loop so that the reader has to unfold them. The
explicit calls are as short and say what they do. The sentinel form is the same fold one step
further: `listOf(null) + items` widens the element type to nullable so that one loop can draw the
"All" entry and the data, where two lines would read as two things because they are two things.
This is the DRY reflex at its least useful, and assistants reach for it constantly.

## When it fires

- The loop is `forEach`, `forEachIndexed`, `map`, `mapIndexed`, `mapNotNull`, `flatMap`,
  `onEach` (collections or sequences) with a lambda, or a `for` loop.
- The looped expression is a literal collection, `listOf` / `setOf` / `arrayOf` / `sequenceOf`
  and their mutable and `NotNull` kin, whose elements are all literals, constants, enum entries
  or objects, with at most `maxElements` elements; a collection of boolean literals at any size.
  Or it is such a collection joined to anything else with `+` (`listOf(null) + days`,
  `days + listOf("Other")`).
- The body branches on the loop variable: it is read in the condition or subject of an `if` or
  `when`, in an `==` / `!=` comparison, as the left side of `?:`, as the receiver of `?.`, or in
  an `is` check.

## When it stays quiet

- The body does not branch on the element: `listOf("a", "b").forEach(::register)` and
  `listOf("a", "b").forEach { register(it) }` loop over data, small as it is.
- The elements are not all literal: `listOf(first, second)` is data.
- The literal list is longer than `maxElements`: `listOf(1, 2, 4, 8, 16)` is a table.
- The loop is over `entries`, `values()` or any expression that is not a literal collection.

## Related rules

[Prefer idiom](prefer-idiom.md) covers the shapes with a standard-library name;
[Narrow local scope](narrow-local-scope.md) and [Live variable budget](live-variable-budget.md)
the other ways a body hides what it does.

## Fixtures

`compiler-tests/testData/diagnostics/noLiteralLoop.kt`

## Implementation notes

`fir/checkers/NoLiteralLoopChecker.kt`: a function-call checker for the loop functions and a
block checker for the desugared `for` loop (the iterator property and the while loop FIR builds
from it). A visitor over the body looks for the loop variable in the branching positions above.

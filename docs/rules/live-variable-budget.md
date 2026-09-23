# Live variable budget

**Diagnostic:** `KOTRAIL_TOO_MANY_LIVE_VARIABLES` (error, on the first statement past the budget)
**Key:** `rules.liveVariableBudget` (on by default)
**Setting:** `max` (default `7`; `0` disables)

## What it rejects

A point in a function where more variables are live than the budget allows:

```kotlin
fun summarize(orders: List<Order>, rate: Double, locale: Locale): String {
    val total = orders.sumOf { it.amount }
    val taxed = total * rate
    val count = orders.size
    val first = orders.first().placedAt
    val last = orders.last().placedAt
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", locale)
    return "..."                     // reported: orders, rate, locale, taxed, count, first, last, formatter
}
```

## What it asks for

Fewer things to hold at once: extract a step into a function that returns one value, compute a
value where it is used rather than up front, or narrow a declaration into the branch that reads
it ([narrow local scope](narrow-local-scope.md) points those out). Function length and cyclomatic
complexity are stand-ins for the reader's load; this rule measures the load itself. The message
lists the live names, so that the extraction to make is concrete.

## How liveness is counted

A variable (a local, a parameter of the function, or a parameter of a lambda inside it) is live at
a statement when it was declared before the statement and is read at or after it, by source
position. Inside a loop, every variable the loop reads is live throughout the loop, since the
next iteration reads it again. A variable declared inside a branch is dead once the branch ends;
one that is declared but never read is never live. A loop variable counts like any local. `x += 1` and `x++` are statements of their own. `this` is not counted. Only the first statement
past the budget is reported, once per function.

## When it stays quiet

- No statement has more than `max` live variables.
- `max` is `0`.

## Fixtures

`compiler-tests/testData/diagnostics/liveVariableBudget.kt`

## Implementation notes

`fir/checkers/LiveVariableBudgetChecker.kt`, a `FirNamedFunctionChecker`. One pass over the body
records every variable's declaration offset and last read, the loops each read sits in, and every
statement of every block; liveness at a statement is then a filter over the variables.

# Complexity (Compose)

**Diagnostics:** `KOTRAIL_COMPOSABLE_TOO_COMPLEX` (error, on the function name), `KOTRAIL_COMPOSABLE_COMPLEXITY_HOTSPOT` (error, on the block that carries most of the points)
**Key:** `rules.compose.complexity` (on by default)
**Settings:** `maxScore` (default `15`), `hotspotShare` (default `40`); `0` for `maxScore` switches the limit off while the records are still written

## What it rejects

A composable that makes a reader hold more state, effects and branches in mind at once than
the limit allows:

```kotlin
@Composable
fun Overview(items: List<Item>, filter: String?, events: Observable<String>) {
    var count by remember { mutableStateOf(0) }            // states 1
    LaunchedEffect(events) { events.collect { count++ } }   // effects 2 + 1 key + 2 writes a state + 1 lives long
    Column {                                                // reported as the hotspot
        items.forEach { item ->                             // branches 1
            val label = filter ?: item.name                 // branches 1
            if (item.selected && count > 0) {               // branches 2
                Text("$label!")
            } else if (item.name.isEmpty()) {               // branches 1
                Text("?")
            } else {
                Text(label)
            }
        }
    }
    Text(count.toString())
}
```

```
e: Overview.kt:49:5 [KOTRAIL_COMPOSABLE_TOO_COMPLEX] [Kotrail] This composable scores 12 (states 1, effects 6, branches 5), limit 8: more state, effects and branches than a reader can hold at once. 'Column { }' carries 5 of them: extract it into a composable of its own. (KOTRAIL_COMPOSABLE_TOO_COMPLEX)
e: Overview.kt:52:5 [KOTRAIL_COMPOSABLE_COMPLEXITY_HOTSPOT] [Kotrail] This block carries 5 of the composable's 12 points: the place to extract. (KOTRAIL_COMPOSABLE_COMPLEXITY_HOTSPOT)
```

(The fixture runs with `maxScore` set to `8`; the default is `15`.)

## What it asks for

Extract the block the hotspot names into a composable of its own. The new composable is scored
on its own, so the points leave the parent:

```kotlin
@Composable
fun Overview(items: List<Item>, filter: String?, events: Observable<String>) {
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(events) { events.collect { count++ } }
    ItemList(items, filter, highlight = count > 0)
    Text(count.toString())
}

@Composable
fun ItemList(items: List<Item>, filter: String?, highlight: Boolean) {
    Column {
        items.forEach { item -> ItemRow(item, filter ?: item.name, highlight) }
    }
}
```

When the points are spread and no block stands out, the message names the kind that dominates
instead: move the states into a state holder the composable receives, move the work behind the
effects into a view model or a holder, give each variant of the UI a composable of its own, or
fold the callbacks into an actions interface.

The score counts what a reader has to hold in mind at once: the sources of change, the code with
a lifetime of its own, the variants of the UI, and the contracts with the caller. That is why it
is not a line count and not a nesting depth. A composable of forty lines with three states, two
effects and six branches is harder to follow than one of eighty lines that lays out a static
tree. An assistant grows a composable one state and one effect at a time, and each step looks
fine on its own; the score is what accumulates.

## The score

| What | Points |
|---|---|
| A state source: `remember { mutableStateOf(...) }`, `rememberSaveable`, `derivedStateOf`, `collectAsState` | 1 each |
| An effect (`LaunchedEffect`, `DisposableEffect`, `produceState`, `SideEffect`) | 2, +1 per key, +2 when it writes a `State`, +1 when its body lives long |
| A `launch` / `async` from a handler | 1 each |
| A branch (`if`, a `when` case, `?:`), a boolean `&&` / `||`, a loop | 1 each |
| A `CompositionLocal.current` read | 1 each |
| A callback parameter beyond four | 1 each |

The four kinds in the breakdown are `states`, `effects` (effects and launches), `branches` and
`coupling` (composition locals and callbacks). An effect's body "lives long" when it collects a
flow, awaits cancellation, waits on frames or pointer events, or loops. A loop is a `for`,
`while` or `do` loop, or a call such as `forEach`, `map`, `items` or `repeat`.

What does not count:

- Line count. [Function length](../function-length.md) owns that.
- Call nesting. [Nesting limit](nesting.md) owns that.
- `remember { }` of a plain object: a remembered formatter or a `Modifier` is not a source of
  change.
- Previews: a `@Preview` function is never scored.

## Hotspots

Every lambda handed to a composable (`Column { }`, `items { }`) and every branch of an `if` or
`when` is a subtree with a subtotal of its own. When the composable is over the limit, the
innermost subtree that carries at least `hotspotShare` percent of the total and at least 5
points is reported on the block as the place to extract. The search goes inward as long as a
child still carries the share, so the block named is the smallest one worth extracting, not the
outermost `Column`.

When no subtree carries the share, the points are spread, and the advice names the dominant
kind instead. The `Dashboard` fixture has four states, four `if` branches and one effect on one
level; nothing stands out, so the message says states are the largest share and asks for a
state holder.

## The report

Every composable's score, its breakdown by kind and its hotspot are written whether or not the
composable is over the limit, to `build/kotrail/complexity/<compilation>/`, one JSON-lines
record per source file. The record has a header line naming the file, then one line per
composable:

```json
{"file": "/path/to/Overview.kt"}
{"function": "com.example.Overview", "line": 49, "score": 12, "states": 1, "effects": 6, "branches": 5, "coupling": 0, "hotspot": "Column { } (5)"}
{"function": "com.example.ItemRow", "line": 31, "score": 1, "states": 0, "effects": 0, "branches": 1, "coupling": 0}
```

A file's record is rewritten whenever the file is compiled, so the records of files an
incremental build did not touch keep what the last build that saw them found. A
`kotrailComplexity` Gradle report that aggregates the records into one view is planned; it
has not shipped yet. Until it does, the records are plain JSON lines under the build directory,
readable with `jq` or any script.

## When it fires

- The function is `@Composable`, has a body, and is not a `@Preview`.
- `maxScore` is above `0` and the score is above it.
- `KOTRAIL_COMPOSABLE_TOO_COMPLEX` is reported on the function name with the score, its
  breakdown, the limit and the advice.
- `KOTRAIL_COMPOSABLE_COMPLEXITY_HOTSPOT` is reported on the block, in addition, when a subtree
  carries at least `hotspotShare` percent of the total and at least 5 points.

## When it stays quiet

- The score is within the limit. `Search` in the fixture scores exactly 8 with two states, one
  effect that writes a state and one branch, and is accepted at `maxScore: 8`.
- The function is not a composable.
- The function is a `@Preview`.
- `maxScore` is `0`:

  ```yaml
  rules:
    compose.complexity:
      maxScore: 0   # no diagnostics; the records for the report are still written
  ```

## Related rules

[Function length](../function-length.md) limits lines, [Nesting limit](nesting.md) limits the
depth of composable calls; this rule limits what the reader has to hold, which neither of those
measures. [Remember keys](remember-keys.md) checks that each effect's keys cover what it reads;
[No global mutable state](no-global-mutable-state.md) covers state the composable reads from
outside. This rule counts how much of all that there is.

## Fixtures

`compiler-tests/testData/diagnostics/compose/complexity.kt` pins: a one-branch item row
accepted, `Search` at the limit accepted, `Overview` reported with the `Column { }` hotspot,
`Dashboard` reported with spread points and no hotspot.

## Implementation notes

`fir/compose/checkers/ComposableComplexityChecker.kt`, a function checker with a scoring
visitor whose nodes mirror the lambda and branch structure of the body: a lambda handed to a
composable (or to a loop call) and each branch of a `when` open a child node, and each node
holds its own points by kind and its children. The callback count comes from the value
parameters with a function type. An `EffectProbe` walks an effect's body for a `State`
assignment and a long-lived call. `fir/compose/ComplexityRecords.kt` writes the records, one
`<sha1 of the path>.jsonl` per source file.

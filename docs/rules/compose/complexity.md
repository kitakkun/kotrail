# Complexity (Compose)

**Diagnostics:** `KOTRAIL_COMPOSABLE_TOO_COMPLEX` (error, on the function name), `KOTRAIL_COMPOSABLE_COMPLEXITY_HOTSPOT` (error, on the block that carries most of the points), `KOTRAIL_COMPOSABLE_HOTSPOT_STANDALONE` (error, on a block that carries `hotspotMin` points in a composable within the limit)
**Key:** `rules.compose.complexity` (on by default)
**Settings:** `maxScore` (default `15`), `hotspotShare` (default `40`), `hotspotMin` (default `10`), `stateFactories` (default none); `0` for `maxScore` switches the limit off while the records are still written, `0` for `hotspotMin` switches standalone hotspots off

## What it rejects

A composable that makes a reader hold more state, effects and branches in mind at once than
the limit allows:

```kotlin
@Composable
fun Overview(items: List<Item>, filter: String?, events: Observable<String>) {
    var count by remember { mutableStateOf(0) }            // states 1
    LaunchedEffect(events) { events.collect { count++ } }   // effects 1 + 1 for writing a state
    Column {                                                // reported as the hotspot
        items.forEach { item ->                             // branches 1
            val label = filter ?: item.name.trim()          // branches 1
            if (item.selected && count > 0) {               // branches 2
                Text("$label!")
            } else if (item.name.isEmpty()) {               // branches 1
                Text("?")
            } else {
                Text(label)
            }
        }
        Text(count.toString())
    }
}
```

```
e: Overview.kt:62:5 [KOTRAIL_COMPOSABLE_TOO_COMPLEX] [Kotrail] This composable scores 8 (states 1, effects 2, branches 5), limit 5: 'Column { }' carries 5 of them: extract it into a composable of its own. (KOTRAIL_COMPOSABLE_TOO_COMPLEX)
e: Overview.kt:65:5 [KOTRAIL_COMPOSABLE_COMPLEXITY_HOTSPOT] [Kotrail] This block carries 5 of the composable's 8 points: the place to extract. (KOTRAIL_COMPOSABLE_COMPLEXITY_HOTSPOT)
```

(The fixture runs with `maxScore` set to `5` and `hotspotMin` set to `4`; the defaults are `15`
and `10`.)

## What it asks for

Take the block the hotspot names out of the composable, in the way that fits the block. A
lambda handed to a composable becomes a composable of its own, scored on its own, so the points
leave the parent while the layout stays where it was:

```kotlin
@Composable
fun Overview(items: List<Item>, filter: String?, events: Observable<String>) {
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(events) { events.collect { count++ } }
    Column {
        ItemList(items, filter, highlight = count > 0)
        Text(count.toString())
    }
}

@Composable
fun ItemList(items: List<Item>, filter: String?, highlight: Boolean) {
    Column {
        items.forEach { item -> ItemRow(item, filter ?: item.name, highlight) }
    }
}
```

The advice follows the kind of block: a `remember { }` hotspot is a computation that wants a plain
function, an effect hotspot wants its body in a non-composable handler or an effect helper of its
own, a branch wants a composable for that variant of the UI, and an event handler lambda wants
a name: its body moved into a function that the call site passes by reference.

When branches dominate a spread score, the advice says where they are. If one block holds at
least half of them, it is named with the advice for its kind ("most of them sit in
'LaunchedEffect { }', move its body into a non-composable handler or an effect helper of its
own"). A composable that returns a value instead of emitting UI (a presenter) has no variant to
split off, so it is asked to move the mapping into a plain function.

When the points are spread and no block stands out, the message says so and names the kinds
that dominate (those carrying at least 30 percent of the total) with the advice for the largest:
move the states into a state holder the composable receives, move the work behind the effects
into a view model or a holder, give each variant of the UI a composable of its own, or fold the
callbacks into an actions interface:

```
e: Dashboard.kt:79:5 [KOTRAIL_COMPOSABLE_TOO_COMPLEX] [Kotrail] This composable scores 9 (states 4, effects 1, branches 4), limit 5: the points are spread across the body with no single block to extract; states (4) and branches (4) dominate: move them into a state holder the composable receives. (KOTRAIL_COMPOSABLE_TOO_COMPLEX)
```

The score counts what a reader has to hold in mind at once: the sources of change, the code with
a lifetime of its own, the variants of the UI, and the contracts with the caller. That is why it
is not a line count and not a nesting depth. A composable of forty lines with three states, two
effects and six branches is harder to follow than one of eighty lines that lays out a static
tree. An assistant grows a composable one state and one effect at a time, and each step looks
fine on its own; the score is what accumulates.

## The score

| What | Points |
|---|---|
| A state source: a call that produces a `State` (`remember { mutableStateOf(...) }`, `rememberSaveable { mutableStateOf(...) }`, `derivedStateOf`, `collectAsState`), or a call listed in `stateFactories`; not `rememberUpdatedState` | 1 each |
| An effect (`LaunchedEffect`, `DisposableEffect`, `produceState`, `SideEffect`) | 1, +1 when it writes a `State`; the branches in its body count as branches |
| A `launch` / `async` from a handler | 1 each |
| A branch (`if`, a `when` case, `?:` with a computed fallback), a boolean `&&` / `||` inside such a condition, a loop | 1 each |
| An `if` / `when` whose every result is a literal, a constant or a reference (`colors.hover`, `theme.colors.idle`): a lookup | 1 in all |
| A `CompositionLocal.current` read | 1 each |
| A callback parameter beyond four | 1 each, at most 4 |

The four kinds in the breakdown are `states`, `effects` (effects and launches), `branches` and
`coupling` (composition locals and callbacks). A state source is recognized by its type: the
outermost call whose result is a `State` scores once, so `remember { mutableStateOf(0) }` is one
point, not two. A project's own wrapper that returns something else (`rememberPersistent`,
say) is named in `stateFactories`, fully qualified, and scores the same point. A loop is a `for`,
`while` or `do` loop, or a call such as `forEach`, `map`, `items` or `repeat`.

What does not count:

- Line count. [Function length](../function-length.md) owns that.
- Call nesting. [Nesting limit](nesting.md) owns that.
- `remember { }` of a plain object: a remembered formatter or a `Modifier` is not a source of
  change.
- A fallback to a literal, a constant or a reference: `color ?: Color.Unspecified`,
  `name ?: ""`, `tint ?: colors.fallback`. It is a default, not a path the reader follows; a
  mapping of twenty tokens with a default each scores nothing. An elvis whose fallback is
  computed (`filter ?: item.name.trim()`, `cached ?: load()`) counts.
- The rows of a lookup. `when { hovered && selected -> Color.A; hovered -> Color.B; else ->
  Color.C }` is one thing to read, so it scores one branch in all, and the `&&` in its rows
  nothing. A reference counts as a constant here: `if (hovered) colors.hover else
  Color.Transparent` and `when { ... -> theme.colors.idle }` are lookups too. A reference is a
  chain of property reads with no call anywhere in it (`theme.colors.idle`, `this.tint`); a
  call is never one, so `colors.hover()` or `palette().hover` is computed. A `when` with a
  computed result in any row scores per row as usual.
- A boolean written as a value: `enabled = a && b`, `val active = hovered && !disabled`. Only
  an `&&` / `||` inside the condition of a counted `if`, `when` row or loop is a path.
- `rememberUpdatedState`: it returns a `State`, but holds a fresh callback for an effect, not
  a source of change.
- How long an effect runs, or how many keys it has. Six `LaunchedEffect(key) { flow.collect { } }`
  collectors in a row score six points, not twenty-four; what an effect does in its body scores
  as branches.
- Callbacks beyond eight: a stateless view with many callbacks is wide, not complex, and the
  coupling points stop at four.
- Previews: a `@Preview` function is never scored.

## Hotspots

Every lambda handed to a composable (`Column { }`, `items { }`), every event handler lambda
(`onClick = { }`, `Modifier.clickable { }`, `onValueChange = { }`), every effect and every branch
of an `if` or `when` is a subtree with a subtotal of its own. When the composable is over the limit, the
innermost subtree that carries at least `hotspotShare` percent of the total and at least 5
points is reported on the block as the place to extract. The search goes inward as long as a
child still carries the share, so the block named is the smallest one worth extracting, not the
outermost `Column`.

A block that spans nearly the whole body (at least 90 percent of its source) is the root layout,
not a block to extract: a composable whose body is one `Row { }` would otherwise be told to
extract all of itself. Such a block is never named as the hotspot, but the search goes on inside
it, so a block within the root layout still can be. The same holds for the advice on a spread
score: "most of them sit in ..." never names a block that spans the body, and the advice falls
back to the one for the kind. The `Board` fixture is one `Column` holding six `if`s; it is
reported with no hotspot and the advice to give each variant of the UI a composable of its own.

A handler lambda is a block too. It is a lambda argument whose parameter is a plain function
type returning `Unit` (or `Nothing`): not `@Composable` (a content slot), not `suspend` (a coroutine body),
without a receiver (a builder), and not run in place by an inline function (`forEach { }`).
Lambdas handed to effects and state producers, and to gesture and drawing callees
(`pointerInput`, `awaitEachGesture`, `awaitPointerEventScope`, any `detect*` or `await*`,
`drawBehind`, `drawWithContent`, `drawWithCache`, `Canvas`), are not handlers. The points inside
a handler were always counted; being a block of its own, it can now be named, by the declared
name of its parameter, with advice of its own:

```kotlin
@Composable
fun Composer(mode: Int, title: String, hint: String) {    // reported: 6
    var draft by remember { mutableStateOf("") }        // states 1
    Text(title)
    Text(hint)
    Text(draft)
    Button(onClick = {                                   // reported as the hotspot: 5
        val text = if (draft.isBlank()) hint.trim() else if (draft.length > 80) draft.take(80) else draft.trim()
        when (mode) {
            0 -> submit("save", text)
            1 -> submit("send", text)
            2 -> submit("schedule", text)
            else -> draft = text
        }
    }) {
        Text("Done")
    }
    Text(mode.toString())
}
```

```
e: Composer.kt:2:5 [KOTRAIL_COMPOSABLE_TOO_COMPLEX] [Kotrail] This composable scores 6 (states 1, branches 5), limit 5: 'onClick { }' carries 5 of them: give it a name: move the body into a function and pass a reference. (KOTRAIL_COMPOSABLE_TOO_COMPLEX)
e: Composer.kt:7:22 [KOTRAIL_COMPOSABLE_COMPLEXITY_HOTSPOT] [Kotrail] This block carries 5 of the composable's 6 points: the place to extract. (KOTRAIL_COMPOSABLE_COMPLEXITY_HOTSPOT)
```

Moved into `fun submitDraft()` and passed as `onClick = ::submitDraft` (or a method on the state
holder), the decision reads as a unit and the layout shows what the button does. Within the
limit, a handler that carries `hotspotMin` points on its own is reported as a standalone hotspot
with the same advice.

When no subtree carries the share, the points are spread, and the advice names the dominant
kinds instead. The `Dashboard` fixture has four states, four `if` branches in one `Column` and
one effect; the `Column` carries four points, under the five a hotspot needs, so the message
says states and branches dominate and asks for a state holder.

### Standalone hotspots

A composable within the limit can still hide one block with many points: a `when (request)`
dispatch inside a single `LaunchedEffect` can carry most of a composable that scores just under
`maxScore`. When a composable is within the limit, the innermost block (not one that spans the
whole body) that carries at least `hotspotMin` points on its own is reported with
`KOTRAIL_COMPOSABLE_HOTSPOT_STANDALONE`, and the composable itself is not reported:

```kotlin
@Composable
fun Dispatcher(request: String, id: String, title: String, subtitle: String) {
    LaunchedEffect(request) {                   // effects 1, reported as a standalone hotspot
        when (request) {
            "open" -> send("open", id)          // branches 1
            "close" -> send("close", id)        // branches 1
            "reload" -> send("reload", id)      // branches 1
            else -> send("unknown", id)
        }
    }
    Column {
        Text(title)
        Text(subtitle)
    }
}
```

```
e: Dispatcher.kt:3:5 [KOTRAIL_COMPOSABLE_HOTSPOT_STANDALONE] [Kotrail] This block carries 4 points on its own: move its body into a non-composable handler or an effect helper of its own. (KOTRAIL_COMPOSABLE_HOTSPOT_STANDALONE)
```

(With `hotspotMin` at `4`, as in the fixture; the default is `10`.) A composable within the limit
and with no such block reports nothing. `0` for `hotspotMin` switches standalone hotspots off.

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
  carries at least `hotspotShare` percent of the total and at least 5 points, and does not span
  the whole body.
- `KOTRAIL_COMPOSABLE_HOTSPOT_STANDALONE` is reported on a block, and nothing on the function
  name, when the score is within the limit but that block carries at least `hotspotMin` points
  on its own (and `hotspotMin` is above `0`).

## When it stays quiet

- The score is within the limit. `Search` in the fixture scores exactly 5 with two states, one
  effect that writes a state and one branch, and is accepted at `maxScore: 5`.
- The fallbacks are constants. `Defaults` in the fixture has six `?: ""` and `?: Labels.NONE`
  and scores nothing.
- The branches pick references. `Swatch` in the fixture is one `Row { }` with an `if` and a
  four-row `when` over `colors.x` and `theme.colors.x`, and a `?: theme.colors.idle`: 2 in all.
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
accepted, `Search` at the limit accepted, `Defaults` with constant fallbacks accepted,
`Styled` with a lookup, a boolean value and a `rememberUpdatedState` accepted, `Overview`
reported with the `Column { }` hotspot, `Dashboard` reported with spread points and no hotspot,
`presenter` (returns a value) asked for a plain function, `Router` told its branches sit in the
effect, `Swatch` with a whole-body `Row { }` of reference lookups accepted, `Dispatcher` within
the limit with its effect reported as a standalone hotspot, `Board` with a whole-body `Column { }`
reported with no hotspot and the generic advice.

## Implementation notes

`fir/compose/checkers/ComposableComplexityChecker.kt`, a function checker with a scoring
visitor whose nodes mirror the lambda and branch structure of the body: a lambda handed to a
composable (or to a loop call), an effect and each branch of a `when` open a child node, and
each node holds its own points by kind, its children and the kind of block it is, which picks
the extraction advice. A state source is a call whose resolved type is a subtype of
`androidx.compose.runtime.State` (the outermost such call in a chain) or whose fully qualified
name is in `stateFactories`. The callback count comes from the value parameters with a function
type. An `EffectProbe` walks an effect's body for a `State` assignment. `fir/compose/ComplexityRecords.kt` writes the records, one
`<sha1 of the path>.jsonl` per source file.

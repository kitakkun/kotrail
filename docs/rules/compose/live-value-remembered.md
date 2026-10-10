# Live value remembered (Compose)

**Diagnostics:** `KOTRAIL_LIVE_VALUE_REMEMBERED` (error, on the `remember` call), `KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE` (error, on the argument)
**Key:** `rules.compose.liveValueRemembered` (on by default)
**Settings:** none

## What it rejects

A `remember` that keeps a copy of a value which changes in place, under keys that do not change
with it:

```kotlin
@Composable
fun TrafficTab(transactions: List<Transaction>) {
    var query by remember { mutableStateOf("") }
    val visible = remember(transactions, query) { transactions.filter { it.url.contains(query) } }
    TransactionList(visible)
}

@Composable
fun TrafficScreen() {
    val transactions = remember { mutableStateListOf<Transaction>() }   // one live list for the whole session
    TrafficTab(transactions)                                            // reported: TrafficTab remembers a copy keyed on it
}
```

`remember` compares its keys with `equals`, and a `SnapshotStateList` is equal to itself: the
screen hands the same list on every composition, so after the first run the key never changes.
The calculation runs again only when a key changes. When transactions are added to the list in
place, `TrafficTab` may well recompose, since the list was read during composition, but on that
recomposition `remember` compares its keys, finds the same list and the same query, and hands back
the copy it made the first time. The tab shows nothing new until `query` happens to change. Strong
skipping plays no part here: the composable does recompose; only the remembered copy is stuck.

The same happens inside one function, where the type says so:

```kotlin
@Composable
fun TrafficTab(transactions: SnapshotStateList<Transaction>, queryState: State<String>) {
    val visible = remember(transactions) { transactions.filter { it.isVisible } }   // reported: 'transactions'
    val length = remember(queryState) { queryState.value.length }                 // reported: 'queryState.value'
}
```

## What it asks for

Derive the value with `derivedStateOf`, whose reads are tracked, so that it follows the
contents of the list and the value of the state. The list stays the key, for a caller that
hands a different list:

```kotlin
@Composable
fun TrafficTab(transactions: List<Transaction>) {
    var query by remember { mutableStateOf("") }
    val visible by remember(transactions) { derivedStateOf { transactions.filter { it.url.contains(query) } } }
    TransactionList(visible)
}
```

The caller's view is the other fix: hand a snapshot (`TrafficTab(transactions.toList())`). Reading
the list during composition is observed, so the screen recomposes when the list changes, the
snapshot is a new list, and the key changes with it. For a state, key the `remember` on the value
read rather than on the `State` object (`remember(query) { query.length }` with a delegated
`query`, or `remember(queryState.value) { ... }`).

## Diagnostics

**`KOTRAIL_LIVE_VALUE_REMEMBERED`**, on a `remember` or `rememberSaveable` call: its calculation
reads a live value that no key reads too. A live value is

- the contents of a snapshot collection (`SnapshotStateList`, `SnapshotStateMap`,
  `SnapshotStateSet`): the collection is the receiver of a call or a property read (`filter`,
  `map`, `size`, `isEmpty()`, `first()`, indexing, a `for` loop, `in`), is destructured
  (`val (first, second) = items`), or is spread. Handed whole as an argument to a call, it is not
  read: `remember(items) { createPager(items) }` may only keep the list, as
  `remember(items) { Pager(items) }` does. Its static type says so, or the local `val` it is read through was initialized with one
  (`val items: List<Tx> = remember { mutableStateListOf() }`), or it is read through a getter
  that returns a stored live property of the same class (`store.history`, below);
- a property delegated to a `State`: `var query by remember { mutableStateOf("") }`,
  `val text by flow.collectAsState()`, a member `var filter by mutableStateOf("")` read through
  its model;
- the `value` of a `State` or `MutableState`.

A key covers a value when it reads it too: `query` for a delegated `query`, `queryState.value`
for `queryState.value`, a read of the contents (`items.toList()`, `items.joinToString()`) for a
snapshot collection. A key that reads only the size or emptiness of a collection (`size`,
`isEmpty()`, `isNotEmpty()`, `lastIndex`, `indices`, `count()` without a predicate) covers only
reads of the same: `remember(list.size) { list.filter { ... } }` is reported, since an element
replaced in place (`list[i] = updated`) leaves the size as it was and the filtered copy keeps the
old element, while `remember(list.size) { list.size * 2 }` is not. The `State` object or the
collection itself as a key covers nothing, since its identity does not change when what it holds
does. One finding per call, naming every value it reads in source
order: `This remember reads 'model.items', 'model.filter', ...`.

**`KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE`**, on an argument of a call to a
composable: the argument is a snapshot collection or a `State`, and the callee's
`identityKeyedParameters` fact lists the parameter it is handed to. The argument holds one by its
static type, through a local `val` initialized with one, as a `remember { mutableStateListOf() }`
written in place, or through a getter that returns a stored live property of the same class
(`val history: List<RunRecord> get() = runs` over `private val runs = mutableStateListOf<RunRecord>()`;
one hop, a getter returning another getter is not followed). An `if`, a `when`, an elvis or a
`try` holds one when any of its branches does (a block by its last expression):

```kotlin
TrafficTab(transactions = if (redact) transactions.map(::redact) else transactions)   // reported: 'transactions'
```

hands the live list on whenever `redact` is false. The message names the argument, or the
branch that holds the live value, and the callee. When the callee only hands the parameter on,
through any number of wrappers, the message also names the composable that holds the
`remember`, where the fix belongs: `'transactions' changes in place, but 'TrafficScreenRoot'
hands it to 'TrafficTab', which remembers a copy keyed on it: ... or let 'TrafficTab' derive
from it with derivedStateOf.`

## The fact

`identityKeyedParameters` lists, for a composable, the parameters it keys a remembered copy on:

- a key of a `remember` or `rememberSaveable` in its body is the parameter itself, or a local
  `val` initialized with it (through any number of such `val`s, a smart cast or `!!`), and the
  calculation reads the parameter's contents (as above: a receiver of a call or a property
  read, a destructuring, a spread; a parameter handed whole to a call, as in
  `remember(items) { createPager(items) }`, is not read, and its callers stay quiet);
- or the parameter is handed as it is (or through such a `val`) to another composable whose fact
  lists the parameter it is handed to. This goes on through any number of wrappers, and the
  entry carries the composable that holds the `remember` (the callee's own holder, or else the
  callee), so that the caller's message can name it. A `remember` of the composable's own wins
  over a callee's.

Only parameters a live value can be passed for are considered: a type that a snapshot
collection or a `State` is a subtype of (`List`, `Collection`, `Iterable`, `Map`, `Set`, `Any`),
or a type parameter. What a caller passes for a `String` or a model class cannot change in
place, and is not written down.

Within a module the fact is computed from the body. For callers in other modules it is written
as the inferred fact `identityKeyedParameters`: in the module's facts file on the JVM and
Android, as `@InferredIdentityKeyedParameters` in a klib (declared in `kotrail-annotations`; the
plugin writes it, nobody writes it by hand). Each entry of `names` is a parameter the composable
remembers a copy of itself, as its bare name (`"transactions"`), or one it hands on, as the name,
`@`, and the composable that holds the `remember` (`"transactions@TrafficTab"`). An entry without
`@`, as every entry was written before holders were recorded, reads as the composable itself. A library compiled
without Kotrail carries no fact, and its composables are taken as remembering nothing. See
[Inferred metadata](../../inferred-metadata.md).

## When it stays quiet

- The read is inside a lambda the calculation creates and does not run: an `onClick`, a
  `lazy { }`, a lambda returned as the remembered value. It reads the value when it is called,
  and sees it then. A lambda an inline function runs in place (`filter { }`, `let { }`,
  `buildList { }`) is part of the calculation.
- The read is inside `derivedStateOf { }` or `snapshotFlow { }`, which observe what they read;
  `remember { derivedStateOf { list.filter { ... } } }` is the fix, not a finding.
- The calculation of a `remember` or `rememberSaveable` without keys is one bare read of a
  state, nothing done to it: `val initial = remember { state.value }`, or
  `val initialCount = remember { count }` over a delegated `count`. That is the idiom for taking
  the initial value once, and neither `derivedStateOf` nor a key is what it wants. A result
  derived from the state is still reported (`remember { state.value.length }`), and so is a
  copy of a snapshot collection (`remember { list.toList() }`); the collection itself
  (`remember { list }`) is its identity, not a read.
- The read is the initial content handed to a state factory (`mutableStateOf(queryState.value)`,
  `items.toMutableStateList()`) or an argument of a constructor (`remember { Holder(items) }`):
  a seed is taken once on purpose, and a holder keeps the live object itself, not a copy.
- The live value is created in the calculation (`remember { mutableStateListOf<Tx>() }`), or
  reached from a call's result rather than from a variable or a property.
- The collection is only handed around, not read: `remember(items) { items }` keeps the live
  list itself, and `remember(items) { createPager(items) }` hands it whole to a call, which may
  only keep it.
- The value read is a plain value: a `List`, a `String`, a model class. Such a value is replaced
  rather than changed, and a new one is a new key; whether it is a key at all is
  [Remember keys](remember-keys.md)' concern.
- A key reads the same value (above); for a collection, a key that reads its elements, or one
  that reads its size when the calculation reads no more than that.
- `LaunchedEffect`, `DisposableEffect` and the other effects: they are about running work, not
  about a value; [Remember keys](remember-keys.md) covers what they capture.
- At a call: the argument is a plain value or a snapshot (`items.toList()`); the callee keys
  nothing on that parameter, or is a library compiled without Kotrail; or the callee's parameter
  is itself declared as a snapshot collection or a `State`. In that last case the `remember`
  inside the callee is reported, and that is where the fix belongs.

## Related rules

[Remember keys](remember-keys.md) reports a changing value the calculation reads that is not a
key, and a key that never changes at all; this rule reports a key that does not change although
what it stands for does. Together they keep a `remember`'s keys honest about what the
calculation depends on.

## Fixtures

`compiler-tests/testData/diagnostics/compose/liveValueRemembered.kt`, and
`compiler-tests/testData/box/compose/liveValueRememberedAcrossModules.kt` with its
`.facts.txt` dump for the fact between a library and its caller.

## Implementation notes

`fir/compose/checkers/ComposableLiveValueRememberedChecker.kt`, a `FirFunctionCallChecker`. A
call to `androidx.compose.runtime.remember` or `androidx.compose.runtime.saveable.rememberSaveable`
with a literal lambda as its last argument is checked for the first diagnostic; its keys are the
arguments of the parameters named `key1`, `key2`, ..., `keys` or `inputs`. Any other call to a
composable is checked for the second. The analysis is in `fir/compose/live/LiveValueAnalysis.kt`:
a `FirVisitorVoid` collects what the calculation does with values in the parts that run while it
runs (inline lambdas whose parameter is neither `noinline` nor `crossinline` are entered; other
lambdas, anonymous objects, local classes and functions, `derivedStateOf`, `snapshotFlow` and
the state factories are not; constructor arguments are skipped). A read of the contents is the
explicit receiver of a call or a property read, the initializer of a destructuring declaration's
`<destruct>` variable, or a spread argument; an argument handed to a call is walked for reads
inside it, but is not one itself. The reads are classified by type: a subtype of `SnapshotStateList<*>`, `SnapshotStateMap<*, *>` or `SnapshotStateSet<*>`, a
property whose delegate's type is a subtype of `State<*>`, or a `value` whose dispatch receiver
is one. A read counts only when the chain of property reads it comes from starts at something
declared outside the calculation. Whether an expression holds a live value under a wider type is
followed through local `val` initializers, a `remember`'s calculation, the branches of a
`FirWhenExpression`, `FirElvisExpression` or `FirTryExpression`, and a member property's getter
whose body is one read of a stored property of the same class through `this`. Reads are matched
against the keys by their dotted path (`model.items`; `this.` dropped, safe calls crossed); a
collection that is the receiver of `size`, `lastIndex`, `indices`, or of `isEmpty()`,
`isNotEmpty()` or `count()` called without arguments, is a size-only read, and a key's size-only
read matches only size-only reads. A
call without keys whose calculation is a single statement that is such a state read (a `!!` or
a smart cast looked through) is not checked. The fact is
`fir/compose/live/IdentityKeyedParametersService.kt`, an inferred fact on the shared base
described in [Inferred metadata](../../inferred-metadata.md), memoized per composable and
transitive through calls; the shared writer records it for non-private composables after
Fir2Ir, from the cache the shared warm-up filled.

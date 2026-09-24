# Parameter order

**Diagnostic:** `KOTRAIL_CALLBACK_BEFORE_DATA_PARAMETER` (error, on the function-typed parameter)
**Key:** `rules.parameterOrder` (on by default)
**Settings:** none
**Fix:** none (call sites that pass the parameters positionally would have to change too)

## What it rejects

A function-typed parameter declared before a data parameter, in a function or a constructor:

```kotlin
fun SnackbarAction(onClick: () -> Unit, label: String)           // reported: onClick before label
class Poller(val onTick: (Long) -> Unit, val intervalMs: Long)   // reported
fun load(transform: (Row) -> Item, rows: List<Row>, limit: Int)  // reported
```

## What it asks for

```kotlin
fun SnackbarAction(label: String, onClick: () -> Unit)
class Poller(val intervalMs: Long, val onTick: (Long) -> Unit)
fun load(rows: List<Row>, limit: Int, transform: (Row) -> Item)
```

A signature reads as "what it works on, then what it does with it"; a callback ahead of the data
it is called with reads backwards. Kotlin's trailing-lambda syntax assumes the same order: only
the last parameter can be passed outside the parentheses, so a function-typed parameter that is
not last forces `f(onClick = { ... }, label = "x")` at every call and buries the lambda body in
the argument list.

## When it fires

Some parameter without a default has a function type (`() -> Unit`, `suspend (T) -> R`,
`@Composable () -> Unit`, a nullable one, a type alias of one, or a type carrying one in a type
argument such as `Pair<() -> Unit, String>`) and a later parameter without a default has a type
that carries no function type. The first such pair is reported.

## When it stays quiet

- Fewer than two parameters, or no function-typed parameter.
- The parameters in question have defaults: an optional callback belongs with the other optional
  parameters, wherever those sit, and a defaulted data parameter does not need a callback after
  it. For a UI composable, [no trailing callback](compose/no-trailing-callback.md) says where the
  optional block and the `content` slot go; the two rules agree.
- `override` and `actual` declarations, whose order is fixed by the supertype or the `expect`.
- Lambdas and anonymous functions.
- A function-typed parameter that follows all the data parameters, however many function-typed
  ones follow it: the order inside each group is the author's.

## Reordering a public declaration

Positional call sites and the `componentN()` functions of a `data class` change with the order,
so the rule reports and leaves the edit to the author. On a public API, reorder in a release
that may break callers, or leave the declaration out with an exclusion
(`exclude: visibility(public)`).

## Fixtures

`compiler-tests/testData/diagnostics/parameterOrder.kt`

## Implementation notes

`fir/checkers/ParameterOrderChecker.kt`, a `FirBasicDeclarationChecker` over named functions and
constructors. A parameter's type is a function type when `isSomeFunctionType` says so after
expansion, which covers suspend, composable, nullable and aliased function types.

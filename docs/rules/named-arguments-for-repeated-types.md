# Named arguments for repeated types

**Diagnostic:** `KOTRAIL_NAMED_ARGUMENTS_REQUIRED` (error, on the whole call)
**Key:** `rules.namedArgumentsForRepeatedTypes` (on by default)
**Settings:** `minArguments` (default `3`), `ignoredFunctions` (default `[kotlin.comparisons.maxOf, kotlin.comparisons.minOf]`)
**Fix:** automatic (`kotrailFix` names every positional argument of the call)

## What it rejects

```kotlin
fun move(x: Int, y: Int, z: Int)
class User(val id: String, val name: String, val email: String)

move(1, 2, 3)                          // 3 positional Int arguments
User("42", "Ann", "ann@example.com")   // 3 positional String arguments
```

Three arguments of one type can be passed in any order and the compiler accepts every
permutation. The bug shows up at runtime, if at all.

## What it asks for

```kotlin
move(x = 1, y = 2, z = 3)
User(id = "42", name = "Ann", email = "ann@example.com")
```

Naming enough of the arguments to drop below the threshold is also fine: `move(1, 2, z = 3)`
leaves two positional `Int`s and is accepted at the default setting.

## When it fires

A resolved call to a Kotlin function or constructor passes at least
`minArguments` **positional** arguments to parameters that share one
**declared** type. Types are compared exactly: `Int` and `Int?` form different groups, and a
generic `T` parameter is one group regardless of what it is inferred to. One diagnostic is
reported per call, describing the largest offending group.

## When it stays quiet

- Fewer positional arguments of one type than the threshold; the callee has fewer parameters than
  the threshold.
- Lambda arguments (trailing or not) never count.
- The callee has a `vararg` parameter (its elements are legitimately positional).
- The callee is listed in `ignoredFunctions` (see below); by default `maxOf` and `minOf`.
- Parameters named `key1`, `key2`, ... do not count: the keys of `remember`, `LaunchedEffect` and
  their kin are an unordered set, and `key1 = state, key2 = tab` says nothing a reader wants.
- The callee is a Java method or constructor: Kotlin cannot name its arguments.
- Operator calls (`grid[1, 2, 3]`, `a + b`), infix calls, and `invoke` calls (`grid(1, 2, 3)`),
  which have no argument names at the call site.
- `minArguments` set to `1` or lower disables the rule.
- Calls with fake sources (desugared constructs such as safe calls) and unresolved calls.

## Functions whose arguments can come in any order

`maxOf(peak, abs(left[i]), abs(right[i]))` returns the same value in every order of its
arguments, so no order can be wrong, and `maxOf(a = peak, b = abs(left[i]), c = abs(right[i]))`
names nothing a reader wants. `ignoredFunctions` lists such functions as globs over the callee's
fully qualified name: a top-level function by its package (`kotlin.comparisons.maxOf`), a member
by its class (`com.acme.Vec.Companion.of`), a constructor as `<class>.<init>`. Every overload of
a matching name is left alone. The default holds `kotlin.comparisons.maxOf` and
`kotlin.comparisons.minOf`, the only functions of the standard library with three or more
parameters of one type whose order cannot matter. Setting the key replaces the list, so a
project that adds its own keeps the defaults by listing them too:

```yaml
rules:
  namedArgumentsForRepeatedTypes:
    ignoredFunctions: [kotlin.comparisons.maxOf, kotlin.comparisons.minOf, com.acme.geometry.average]
```

A function such as `lerp(a, b, t)` does not belong on the list: swapping `b` and `t` compiles and
returns another value.

## Fixtures

- `compiler-tests/testData/diagnostics/namedArguments.kt` (default threshold `3`)
- `compiler-tests/testData/diagnostics/config/namedArgumentsThreshold.kt` (threshold `2`)
- `compiler-tests/testData/diagnostics/config/namedArgumentsIgnoredFunctions.kt` (a project's own `ignoredFunctions`)
- `compiler-tests/testData/diagnostics/config/namedArgumentsIgnoredNone.kt` (an empty `ignoredFunctions`, set in `namedArgumentsIgnoredNone.yaml`)

## Implementation notes

`fir/checkers/NamedArgumentsChecker.kt`, a `FirFunctionCallChecker`. The resolved argument list
(`FirResolvedArgumentList`) drops the `FirNamedArgumentExpression` wrappers, so the checker reads
`originalArgumentList` to tell named from positional arguments and pairs it by position with
`mapping`, which keeps source order. Without a vararg parameter both lists have the same length;
otherwise the call is skipped. Parameter types come from the callee's `returnTypeRef.coneType`
(unsubstituted), grouped with cone type equality.

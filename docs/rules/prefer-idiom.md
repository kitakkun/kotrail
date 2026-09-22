# Prefer idiom

**Diagnostic:** `KOTRAIL_PREFER_IDIOM` (error, on the expression)
**Key:** `rules.preferIdiom` (on by default)
**Settings:** `disabled` (default `[]`), `chains` (default `[]`), `calls` (default `[]`)
**Fix:** automatic (`kotrailFix` rewrites the expression)

## What it rejects

A spelled-out expression for which the standard library has one name, matched by the type of
the receiver rather than by the text:

| Key | Written | Idiom |
|---|---|---|
| `emptiness` | `x.size == 0`, `x.length != 0`, `x.size > 0`, `x.size >= 1`, `x.count() == 0` | `x.isEmpty()`, `x.isNotEmpty()` |
| `negation` | `!x.isEmpty()`, `!x.isBlank()` (and the reverse) | `x.isNotEmpty()`, `x.isNotBlank()` |
| `nullOrEmpty` | `x == null \|\| x.isEmpty()`, `x == null \|\| x.isBlank()` | `x.isNullOrEmpty()`, `x.isNullOrBlank()` |
| `chain` | `xs.filter { p }.first()`, `.firstOrNull()`, `.any()`, `.isNotEmpty()`, `.size`, ...; `xs.map { f }.filterNotNull()` | `xs.first { p }`, `xs.any { p }`, `xs.count { p }`, ...; `xs.mapNotNull { f }` |
| `elvis` | `if (x != null) x else y` | `x ?: y` |

## What it asks for

The named form. `isEmpty()` says what is being asked where `size == 0` makes the reader work it
out; `first { p }` says one pass where `filter { p }.first()` reads as two and allocates one;
`x ?: y` is the operator the language has for the question. Each is what an experienced Kotlin
reader writes without thinking, and each is what an assistant produces the long way because
the long way also compiles.

The message carries the exact rewrite and the idiom's key, and `kotrailFix` applies it.

## Why the types matter

`box.size == 0` on a class of one's own is not an emptiness check, and `!node.isEmpty()` on a
class that happens to declare `isEmpty` has no `isNotEmpty` to go with it. The rule therefore
matches `emptiness`, `negation`, and `nullOrEmpty` only on receivers that are a `Collection`,
`Map`, `Array`, or `CharSequence`, and `chain` only on an `Iterable`, `Array`, or `Sequence`
pipeline. That is what makes the rule a compiler check rather than a text search.

## Settings

`disabled` lists the keys a project does not want asked for; `chains` and `calls` add the
project's own idioms, matched by fully qualified name rather than by type:

```yaml
rules:
  preferIdiom:
    disabled: [chain, elvis]
    chains:
      - kotlinx.coroutines.flow.filter then kotlinx.coroutines.flow.first -> kotlinx.coroutines.flow.first
      - com.acme.Query.where then com.acme.Query.single -> com.acme.Query.singleWhere
    calls:
      - kotlin.collections.getOrNull(0) -> kotlin.collections.firstOrNull
```

A chain entry says: a call to the first function, with one argument, whose result is the
receiver of a call to the second function with none, is written as the replacement with the
first call's argument (`query.where { p }.single()` becomes `query.singleWhere { p }`). A call
entry says: a call to the function with exactly that literal argument is written as the
replacement with none (`items.getOrNull(0)` becomes `items.firstOrNull()`). Every name is fully
qualified, the replacement included, so that it is clear which `firstOrNull` is meant: the rule
checks that the replacement exists, as a top-level function of that package or a member of that
class, and asks for nothing when it does not. The rewrite itself uses the short name, so the
function has to be importable where the idiom is applied. Both come with a fix and are reported
under the key `chains` or `calls`. Write them as a YAML sequence: the shorthand list form splits
on commas.

## When it stays quiet

- The receiver is not of the kind the idiom belongs to: `size` of a class of one's own, a
  number compared with something other than zero or one.
- The two sides of `x == null || x.isEmpty()` are different values, or the check is negated
  (`x != null && x.isNotEmpty()` is left as written).
- The `filter` feeds anything other than the terminals listed, or is passed something other
  than one argument.
- The `if` does not return the checked value itself in the first branch, has more than two
  branches, or has a subject.

## Fixtures

`compiler-tests/testData/diagnostics/preferIdiom.kt`,
`compiler-tests/testData/diagnostics/config/preferIdiomDisabled.kt`

## Implementation notes

`fir/checkers/PreferIdiomChecker.kt`, one checker per shape: `FirEqualityOperatorCallChecker` and
a `FirBasicExpressionChecker` for `FirComparisonExpression` (`emptiness`), `FirFunctionCallChecker`
on `Boolean.not` (`negation`), `FirBooleanOperatorExpressionChecker` (`nullOrEmpty`),
`FirQualifiedAccessExpressionChecker` (`chain`, which covers `.size` as well as calls), and
`FirWhenExpressionChecker` (`elvis`). Receiver kinds are decided with
`AbstractTypeChecker.isSubtypeOf` against the star-projected standard types. The fix rebuilds the
expression from the receiver's and the argument's source text.

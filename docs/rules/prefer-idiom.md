# Prefer idiom

**Diagnostic:** `KOTRAIL_PREFER_IDIOM` (error, on the expression)
**Key:** `rules.preferIdiom` (on by default)
**Setting:** `disabled` (default `[]`)
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

`disabled` lists the keys a project does not want asked for:

```yaml
rules:
  preferIdiom:
    disabled: [chain, elvis]
```

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

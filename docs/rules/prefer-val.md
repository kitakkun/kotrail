# Prefer val

**Diagnostic:** `KOTRAIL_PREFER_VAL` (error, on the property name)
**Key:** `rules.preferVal` (on by default)
**Settings:** none
**Fix:** automatic (`kotrailFix` replaces the keyword)

## What it rejects

A `var` that nothing reassigns after its initializer:

```kotlin
fun total(items: List<Item>): Int {
    var sum = items.sumOf { it.price }     // reported
    return sum
}

class Counter(seed: Int) {
    private var base = seed                // reported: no member assigns to it
}
```

## What it asks for

```kotlin
val sum = items.sumOf { it.price }
private val base = seed
```

`var` is a promise to the reader that the value changes, and every line after the declaration
has to be read with that possibility in mind. When nothing assigns to it, `val` says so where
the reader looks first. Assistants reach for `var` "in case it changes later" and then never
change it; this is where the compiler can hold the line.

## When it fires

The property is a `var` with an initializer, and no assignment to it exists in the scope from
which it could be assigned:

- a local: the body that declares it, lambdas inside that body included;
- a private member: its class, nested classes and companion included;
- a private top-level property: its file.

Compound assignments (`x += 1`) and increments (`x++`) count as assignments.

## When it stays quiet

- The property is not private (anything with access could assign it), or is `open`,
  `override`, `expect`, or `actual`.
- It has no initializer (assigned later, perhaps in branches), a custom getter or setter, a
  delegate, or is `lateinit` or `const`.
- It is a component of a destructuring declaration.

## Fixtures

`compiler-tests/testData/diagnostics/preferVal.kt`

## Implementation notes

`fir/checkers/PreferValChecker.kt`, a `FirPropertyChecker`. The scope is the last function,
initializer, or property in `CheckerContext.containingDeclarations` for a local, the containing
class for a member, and the file for a top-level property; a `FirVisitorVoid` looks for a
`FirVariableAssignment` whose left side (through `FirDesugaredAssignmentValueReferenceExpression`
for `+=` and `++`) resolves to the property. The fix replaces the `var` token found in the
declaration's source text.

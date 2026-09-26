# Named condition

**Diagnostic:** `KOTRAIL_CONDITION_DUPLICATED`, `KOTRAIL_CONDITION_TOO_COMPOUND` (error, on the condition)
**Key:** `rules.namedCondition` (on by default)
**Settings:** `minOperators` (default `2`), `maxOperators` (default `3`)

## What it rejects

The same compound condition written in more than one place:

```kotlin
fun measure(node: Node, options: Options): Int {
    if (!node.visible && !options.includeInvisible && node.children.isEmpty()) return 0
    // ...
}

fun draw(node: Node, options: Options) {
    if (!node.visible && !options.includeInvisible && node.children.isEmpty()) return   // reported: also in measure
    // ...
}
```

and a lone inline condition too compound to read in place:

```kotlin
if (form.isValid && !form.isSubmitting && user != null && user.canPost) submit()   // reported: 3 operators
if (isAdmin && hasToken || isGuest) open()                                          // reported: && and || mixed
```

## What it asks for

```kotlin
private fun Node.isSkipped(options: Options): Boolean =
    !visible && !options.includeInvisible && children.isEmpty()

if (node.isSkipped(options)) return
```

or a named `val` in front of the branch (`val canSubmit = form.isValid && ...; if (canSubmit)`).
An inline condition says what it computes but not what it decides; a name says what it decides,
and the reader learns that before the how. The same compound condition written in two places is
one decision with two definitions: one copy gets the new case and the other does not. Mixing
`&&` and `||` without parentheses is where readers slip on precedence.

## When it fires

- `KOTRAIL_CONDITION_DUPLICATED`: a condition of an `if`, a subject-less `when` branch, a `while`
  or a `do`-`while` with at least `minOperators` `&&` / `||` operators whose text, with whitespace
  and comments removed and everything else kept verbatim (identifiers included), equals another
  such condition elsewhere in the same module: another function, another file. The message names
  the other occurrence as `File.kt:line`.
- `KOTRAIL_CONDITION_TOO_COMPOUND`: such a condition with at least `maxOperators` operators
  (`!` does not count), or with an `||` whose operand is an unparenthesized `&&` (`a && b || c`).
  A condition reported as duplicated is not reported again as compound.

## When it stays quiet

- The condition is already a name: `val ready = a && b && c` followed by `if (ready)`. The
  initializer of a `val` is not a condition position and is never looked at.
- Conditions below the thresholds: most two-operator conditions read fine inline
  (`event.isKeyDown && event.key == Key.Q`).
- An `&&` whose left side is a null comparison (`x != null && x.isEmpty()`) is a smart-cast guard,
  not a decision, and does not count toward `maxOperators`.
- `when (subject)` branches (`is A, is B ->`, `in range ->`, constants), and the arguments of
  `require`, `check`, `assert` and any other call: they are not control-flow conditions.
- Test functions (by `test.annotations`) and files in a test source set (`src/test/`,
  `src/jvmTest/`, `src/androidUnitTest/` and kin), where spelled-out repetition is the point.
- The first occurrence of a duplicated condition: occurrences are indexed in memory while the
  module is checked, in the order the compiler visits files, so the copy seen first finds nothing
  and every later copy is reported, naming the first. Which copy counts as first across files
  depends on that order; at least one of every pair is reported.

## Related rules

[Prefer idiom](prefer-idiom.md) rewrites `x == null || x.isEmpty()` into `x.isNullOrEmpty()`,
which also removes the operator this rule would count; [Function length](function-length.md)
keeps the body the condition sits in small.

## Fixtures

`compiler-tests/testData/diagnostics/namedCondition.kt`: two files in one module, with the
duplicate across functions in one file and across the two files.

## Implementation notes

`fir/checkers/NamedConditionChecker.kt`: a `when` checker (an `if` is a subject-less `when` in
FIR) and a loop checker share one path. Parentheses are gone in FIR, so an unparenthesized `&&`
under an `||` is found from the source text between the two operators' starts. The duplicate
index, `NamedConditionIndex`, is a FIR session component, so it lives for one module's
compilation and records nothing to disk.

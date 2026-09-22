# Narrow local scope

**Diagnostic:** `KOTRAIL_NARROW_LOCAL_SCOPE` (error, on the local's name)
**Key:** `rules.narrowLocalScope` (on by default)
**Settings:** none
**Fix:** automatic when the branch has braces (`kotrailFix` moves the declaration to the top of the branch)

## What it rejects

A local `val` that only one branch below it reads:

```kotlin
val name = user.name              // reported: only the else branch reads it
if (user.isGuest) {
    Text("Guest")
} else {
    Text(name)
}
```

## What it asks for

```kotlin
if (user.isGuest) {
    Text("Guest")
} else {
    val name = user.name
    Text(name)
}
```

Every declaration a reader passes is one more thing to carry until it is used, and the further
it is carried the more of the function has to be read with it in mind. A value that matters to
one branch belongs in that branch: the reader meets it where it is needed, and the branches
that do not need it are shorter to think about.

## When it fires

All of the following hold:

- The local is a `val` with an initializer that can move without changing anything: a literal,
  a read of a variable or property, `this`, an object, a string template, an elvis, or built-in
  operators (`+`, `*`, ...) over those. A call is not moved, since it could run at a different
  time, or not at all.
- After the declaration, the local is never read directly in the declaring block: not in a
  statement, not in an `if` condition, not in a `when` subject or branch condition.
- Every read sits inside the same branch block of one `if`, `when`, or `try` (its `try`,
  `catch`, or `finally` block) that follows in the declaring block, at any depth inside that
  branch.

## When it stays quiet

- The initializer calls a function or constructor, or contains a lambda.
- The local is read in the declaring block itself, or in two or more branches.
- A read sits inside a loop body or a lambda that is not itself inside one branch: moving the
  declaration in would evaluate it on every iteration or later.
- The local is a `var`, is delegated, or has no initializer.

A branch without braces (`if (c) println(x)`) is reported, since the reader still carries the
value, but no fix is offered: there is nowhere to move the declaration to without adding braces.

## Fixtures

`compiler-tests/testData/diagnostics/narrowLocalScope.kt`

## Implementation notes

`fir/checkers/NarrowLocalScopeChecker.kt`, a `FirPropertyChecker` over locals. The declaring
block is the last `FirBlock` in `CheckerContext.containingElements`; a `FirVisitorVoid` over the
statements after the declaration records, for each read, the first branch block entered on the
way to it (`FirWhenBranch.result`, the blocks of a `FirTryExpression`), pins the declaration on a
read outside any such block, and treats a read inside a loop, lambda, local function, or class
that is not itself inside a branch as pinning too. The fix deletes the declaration's line and
inserts the declaration, with the branch's indentation, before the branch's first statement.

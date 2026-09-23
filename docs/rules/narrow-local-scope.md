# Narrow local scope

**Diagnostics:** `KOTRAIL_NARROW_LOCAL_SCOPE`, `KOTRAIL_LOCAL_DECLARED_TOO_EARLY` (error, on the local's name)
**Key:** `rules.narrowLocalScope` (on by default)
**Setting:** `maxDistance` (default `5`; `0` switches the distance check off)
**Fix:** automatic (`kotrailFix` moves the declaration to the top of the branch, when it has braces, or to just before the first use)

## What it rejects

A local `val` that only one branch below it reads, or one that is first read long after it is
declared:

```kotlin
val name = user.name              // reported: only the else branch reads it
if (user.isGuest) {
    Text("Guest")
} else {
    Text(name)
}
```

```kotlin
val greeting = "Hello, ${user.name}"   // reported: first used 7 lines below
println("one")
...
println("six")
println(greeting)
```

## What it asks for

```kotlin
if (user.isGuest) {
    Text("Guest")
} else {
    val name = user.name
    Text(name)
}

println("six")
val greeting = "Hello, ${user.name}"
println(greeting)
```

Every declaration a reader passes is one more thing to carry until it is used, and the further
it is carried the more of the function has to be read with it in mind. A value that matters to
one branch belongs in that branch: the reader meets it where it is needed, and the branches
that do not need it are shorter to think about.

## When it fires

The branch shape, `KOTRAIL_NARROW_LOCAL_SCOPE`, when all of the following hold:

- The local is a `val` with an initializer that can move without changing anything: a literal,
  a read of a variable or property, `this`, an object, a string template, an elvis, or built-in
  operators (`+`, `*`, ...) over those. A call is not moved, since it could run at a different
  time, or not at all.
- After the declaration, the local is never read directly in the declaring block: not in a
  statement, not in an `if` condition, not in a `when` subject or branch condition.
- Every read sits inside the same branch block of one `if`, `when`, or `try` (its `try`,
  `catch`, or `finally` block) that follows in the declaring block, at any depth inside that
  branch.

The distance shape, `KOTRAIL_LOCAL_DECLARED_TOO_EARLY`, when the branch shape does not apply,
the initializer is pure in the same sense, and more than `maxDistance` lines separate the end of
the declaration from the start of the statement that first reads the local. The lines in between
are, by construction, statements that do not use it; the fix moves the declaration to just before
that statement.

For both shapes, the `var`s the initializer reads are its dependencies: the declaration moves
only if no statement it would move past (and, for the branch shape, nothing in the branch
statement outside the target block) assigns one of them, and none of them is assigned inside a
lambda or local function, which any call in between could run. A `val` read cannot change and is
no obstacle.

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

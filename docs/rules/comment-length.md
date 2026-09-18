# Comment length

**Diagnostic:** `KOTRAIL_COMMENT_TOO_LONG` (error, on the comment)
**Key:** `rules.commentLength` (on by default)
**Settings:** `maxLines` (default `5`), `maxKDocLines` (default `0`, unlimited)

## What it rejects

Long comments that narrate the code instead of documenting it:

```kotlin
// First we take the list of users that was passed in.
// Then we filter out the ones that are not active.
// After that we map each remaining user to its display name.
// The result is sorted alphabetically so the UI is stable.
// Finally the sorted list is returned to the caller.
// Note that this does not mutate the input list.
return users.filter { it.active }.map { it.displayName }.sorted()
```

## What it asks for

Say less, or move the explanation into names and structure. Documentation that genuinely
needs length belongs in KDoc, which has its own budget.

## How length is measured

- A run of `//` comments on consecutive lines, each starting its line, is one block; its
  length is the number of lines. A blank line, code, or a trailing comment (`val x = 1 // ...`)
  ends the run. Trailing comments themselves never count.
- A `/* */` comment counts the lines it spans.
- A KDoc (`/** */`) counts the lines it spans, against `maxKDocLines`.

A block is reported once, on its whole range, when it exceeds the limit. `0` disables a limit.

## When it stays quiet

Comment-looking text inside string literals, raw strings, templates, and character literals is
not a comment and is ignored; nested block comments are handled.

## Suppressing

Comments belong to no declaration, so suppression is per file:

```kotlin
@file:Suppress("KOTRAIL_COMMENT_TOO_LONG")   // e.g. a file with a long license header
```

## Fixtures

`compiler-tests/testData/diagnostics/commentLength.kt`

## Implementation notes

Comments are not part of FIR, and the platform's syntax-tree types are shaded differently in
the embeddable compiler, so `fir/checkers/CommentLengthChecker.kt` reads the file text through
`KtSourceElement.text`, finds comments with the small hand-written
`comments/CommentScanner.kt`, and reports on a fake source element carrying the comment's
offsets. That keeps the plugin free of IntelliJ platform classes, which is what lets the same
JAR run under both the embeddable compiler and the un-shaded test framework.

Deliberately not attempted: judging whether a comment merely restates the code. That is a
fuzzy, review-level judgment; the compiler is the wrong place for it.

# Suppress reason

**Diagnostic:** `KOTRAIL_SUPPRESS_WITHOUT_REASON` (error, on the annotation)
**Key:** `rules.suppressReason` (on by default)
**Settings:** `annotations` (default: `kotlin.Suppress`, `android.annotation.SuppressLint`), `ids` (default: `KOTRAIL_*`)

## What it rejects

A suppression annotation that carries no written reason:

```kotlin
@Suppress("KOTRAIL_CATCH_TOO_BROAD")      // reported: why is the broad catch fine here?
fun poll() {
    try { device.read() } catch (e: Exception) { log(e) }
}
```

## What it asks for

```kotlin
// The SDK throws its own base type; anything else here is a bug in the SDK.
@Suppress("KOTRAIL_CATCH_TOO_BROAD")
fun poll() {
    try { device.read() } catch (e: Exception) { log(e) }
}
```

or the same on the annotation's line: `@Suppress("KOTRAIL_CATCH_TOO_BROAD") // the SDK throws
its own base type`. A suppression silences a check for every later reader, who then has to
guess why; the reason belongs next to the annotation, not in a commit message or a review
thread that the reader will not find. An assistant that runs into a Kotrail error reaches for
`@Suppress` first; asked for a reason, it either writes the real one down or finds out that
there is none and fixes the code.

## When it fires

- A declaration, or the file (`@file:Suppress(...)`), carries an annotation from `annotations`
  (`kotlin.Suppress` and `android.annotation.SuppressLint` by default), and one of the
  annotation's string arguments matches a glob in `ids` (`KOTRAIL_*` by default: a silenced
  Kotrail check is what a reviewer needs explained). `ids: ["*"]` checks every suppression.
- No comment is on the annotation's line (before or after it, `//` or a block), on the line
  directly above it (a `//` line, or the line a block comment or KDoc ends on), or above the
  group it belongs to (below).
- Any comment text counts; the rule does not judge it. A blank line between a comment and the
  annotation breaks the link: the comment is about something else.

## When it stays quiet

- A comment above a group of suppressed declarations counts for the whole group: walking up
  from the annotation, a declaration that carries a checked suppression is a member, and a
  single blank line between members is crossed; the walk stops at a declaration without one.

  ```kotlin
  // Legacy entry points kept for the old callers until they migrate.
  @Suppress("KOTRAIL_CATCH_TOO_BROAD") fun pollV1() { ... }

  @Suppress("KOTRAIL_CATCH_TOO_BROAD") fun pollV2() { ... }    // fine: the comment covers the group

  fun pollV3() { ... }

  @Suppress("KOTRAIL_CATCH_TOO_BROAD") fun pollV4() { ... }    // reported: pollV3 ended the group
  ```
- An annotation not on `annotations` (`@OptIn` is a contract, not a suppression), or a
  suppression none of whose ids matches `ids`: a self-evident `@Suppress("UNCHECKED_CAST")` on
  a serializer is noise to explain, so it is not checked unless the project widens `ids`:

  ```yaml
  rules:
    suppressReason:
      ids: ["KOTRAIL_*", "DEPRECATION", "UNCHECKED_CAST"]
      annotations: [kotlin.Suppress, android.annotation.SuppressLint, com.acme.lint.Ignore]
  ```
- The annotation is one of several on the declaration: a comment above the stack, or on the
  annotation's own line, is what counts; the other annotations' lines are walked through.
- Generated code, by the project's `generated` settings, like every rule.

## Related rules

Every other rule: this one keeps their suppressions honest. The `@Suppress` names a rule's
diagnostic; the comment says why the rule does not apply here.

## Fixtures

`compiler-tests/testData/diagnostics/suppressReason.kt`

## Implementation notes

`fir/checkers/SuppressReasonChecker.kt`, a basic declaration checker that sees every
annotated declaration and the file: for each annotation from `annotations` whose string
arguments match `ids`, it asks `comments/SuppressionReason.kt` whether the file's text has a
comment on the annotation's line, above it, or above its group; FIR keeps no comment nodes, so
the decision is made on the source text around the annotation's offsets. A property made from a
constructor parameter and a default accessor share the annotations written on the parameter or
the property; only the targeted ones (`@property:`, `@get:`) are checked there, so each
annotation is reported once.

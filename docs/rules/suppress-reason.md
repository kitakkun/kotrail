# Suppress reason

**Diagnostic:** `KOTRAIL_SUPPRESS_WITHOUT_REASON` (error, on the annotation)
**Key:** `rules.suppressReason` (on by default)
**Settings:** `annotations` (default: `kotlin.Suppress`, `android.annotation.SuppressLint`), `ids` (default: `*`), `exemptIds` (default: `unused`, `UNUSED`, `UNUSED_*`)

## What it rejects

A suppression annotation that carries no written reason, whatever check it silences:

```kotlin
@Suppress("KOTRAIL_CATCH_TOO_BROAD")      // reported: why is the broad catch fine here?
fun poll() {
    try { device.read() } catch (e: Exception) { log(e) }
}

@Suppress("UNCHECKED_CAST")               // reported: why is the cast safe?
fun <T> decode(raw: Any): T = raw as T
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
  annotation's string arguments matches a glob in `ids` (`*` by default: every suppression owes
  a reason) and no glob in `exemptIds`. A Kotrail check, an `UNCHECKED_CAST` (why the cast is
  safe) and a `DEPRECATION` (why the old API is still used) are all worth a line.
- An annotation that mixes an exempt id with another, `@Suppress("unused", "DEPRECATION")`, is
  checked: the message quotes the annotation as written, so it names the id that owes the
  reason.
- No comment is on the annotation's line (before or after it, `//` or a block), on the line
  above, or a comment block that ends within two blank lines above the annotation (or the
  first member of a group of suppressed declarations, below); the line above may be a `//`
  line, or the line a block comment or KDoc ends on.
- A file annotation (`@file:Suppress(...)`) takes only a comment on its own line or on the
  line directly above: a license or file header followed by a blank line is not its reason,
  and a file annotation is never part of a group.
- Any comment text counts; the rule does not judge it. Three or more blank lines between a
  comment and the annotation break the link, and so does any other line: the comment is about
  something else.

## When it stays quiet

- A comment above a group of suppressed declarations counts for the whole group: walking up
  from the annotation, a declaration that carries a checked suppression is a member, and a
  single blank line between members is crossed; the walk stops at a declaration without one.
  A section comment set off from the first member by one or two blank lines still counts.

  ```kotlin
  // Legacy entry points kept for the old callers until they migrate.
  @Suppress("KOTRAIL_CATCH_TOO_BROAD") fun pollV1() { ... }

  @Suppress("KOTRAIL_CATCH_TOO_BROAD") fun pollV2() { ... }    // fine: the comment covers the group

  fun pollV3() { ... }

  @Suppress("KOTRAIL_CATCH_TOO_BROAD") fun pollV4() { ... }    // reported: pollV3 ended the group
  ```
- An annotation not on `annotations` (`@OptIn` is a contract, not a suppression), or a
  suppression none of whose ids matches `ids`.
- A suppression whose ids all match `exemptIds` (`unused`, `UNUSED` and `UNUSED_*` by default): a member
  invoked through a framework (a serializer, a reflection-bound callback) carries
  `@Suppress("unused")` for a reason that is always the same, so writing it down every time
  says nothing. A project that wants only Kotrail's suppressions explained, or more ids
  exempted, sets the lists:

  ```yaml
  rules:
    suppressReason:
      ids: ["KOTRAIL_*"]
      exemptIds: ["unused", "UNUSED", "UNUSED_*", "NAME_SHADOWING"]
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
annotated declaration and the file: for each annotation from `annotations` with a string
argument that matches `ids` and not `exemptIds` (`comments/SuppressedIds.kt`), it asks `comments/SuppressionReason.kt` whether the file's text has a
comment on the annotation's line, above it, or above its group; FIR keeps no comment nodes, so
the decision is made on the source text around the annotation's offsets. A property made from a
constructor parameter and a default accessor share the annotations written on the parameter or
the property; only the targeted ones (`@property:`, `@get:`) are checked there, so each
annotation is reported once.

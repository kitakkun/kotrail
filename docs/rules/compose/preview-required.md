# Preview required (Compose)

**Diagnostic:** `KOTRAIL_COMPOSABLE_WITHOUT_PREVIEW` (error, on the function name)
**Key:** `rules.compose.previewRequired` (on by default)
**Setting:** `scope` (`public`, `internal` (default: public and internal), `all`)

## What it rejects

A UI composable whose file contains no `@Preview` composable that calls it:

```kotlin
@Composable
fun UserCard(name: String, modifier: Modifier = Modifier) { ... }   // nothing else in the file
```

## What it asks for

```kotlin
@Composable
fun UserCard(name: String, modifier: Modifier = Modifier) { ... }

@Preview
@Composable
private fun UserCardPreview() {
    UserCard(name = "Ada")
}
```

AI assistants produce components without previews as a matter of course; a preview is what
lets a reviewer, and the assistant itself, look at the component. The rule also fixes the
convention that the preview lives in the same file as the component.

## When it fires

For each `Unit`-returning `@Composable` function in the file (top-level, or a member of a class
or object) whose visibility is within `scope`: no function in the same file
annotated with `@Preview`, or with a multipreview annotation (an annotation class itself
annotated with `@Preview`, such as `@PreviewLightDark` or a project-defined `@ThemePreviews`),
calls it directly.

## When it stays quiet

- Private composables (helpers), value-returning composables, and preview functions themselves.
- `override`, `expect`, and `actual` functions, local functions, and members without a body
  (an abstract composable has nothing to render; its implementations are what previews call).
- The composable is called from a preview in the same file, at any call depth inside the
  preview's body (a preview wrapped in a theme lambda still counts).
- Another overload of the same name is called from a preview: overloads are one component, as
  in [composables per file](composables-per-file.md), so one preview covers them all.

Composables that cannot be previewed (they take a ViewModel, need a navigation controller)
should be suppressed at the spot with `@Suppress("KOTRAIL_COMPOSABLE_WITHOUT_PREVIEW")`, or better,
split into a thin stateful wrapper and a previewable stateless component. The suppression works
on the composable, on a class or object around it, or on the file.

## Fixtures

`compiler-tests/testData/diagnostics/compose/previewRequired.kt`

## Implementation notes

`fir/compose/checkers/ComposablePreviewRequiredChecker.kt`, a `FirFileChecker`. Because the
requirement is same-file, the check is a single-file frontend pass: it collects the file's
functions, gathers the resolved callees of every preview body, and reports the composables that
are not among them. Previews in other source sets or modules are deliberately not considered.
Because a file checker runs with the file's context, `@Suppress` on a composable or on a class
around it is read from the declarations themselves (through the compiler's own
`getDiagnosticsSuppressedForContainer`) rather than from the context.

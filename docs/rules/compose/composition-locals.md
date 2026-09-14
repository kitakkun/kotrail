# Composition locals (Compose)

**Diagnostics:**
`KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED` (error, on the root function name),
`KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED_AT_ENTRY_POINT` (error, on the entry point's name)
**Switch:** `rules.compose.compositionLocals` (default `true`)
**Settings:** `compose.compositionLocals.platform`, `.required`, `.roots`, `.known[<fqn>]`
**Artifact:** `annotations` (`com.kitakkun.kotrail.compose.locals`), needed on the compile
classpath of every module the plugin is applied to.

## The problem

A composition local whose default throws is a runtime contract: read it where nothing above
provided it and the screen crashes, usually in a preview or in the one navigation path nobody
clicked. Nothing in the type system says which composables read `LocalNavController`, so an
assistant that moves a composable to a new screen, or writes a preview for it, cannot see the
contract it is breaking.

## What it rejects

At a **root of composition**, every required local that is read anywhere below the root and
never provided on the way:

```kotlin
val LocalNavController = compositionLocalOf<NavController> { error("No NavController provided") }
val LocalTitle = compositionLocalOf { "untitled" }        // has a default: never required

@Composable fun TopBar() { LocalNavController.current; LocalTitle.current }
@Composable fun HomeScreen() { TopBar() }

@CompositionLocalRoot
@Composable
fun AppRoot() {                          // reported: 'LocalNavController' is read in HomeScreen > TopBar
    HomeScreen()
}

@Preview
@Composable
fun HomePreview() {                      // reported: a preview is a root, and nothing provides anything to it
    HomeScreen()
}
```

## What it asks for

Provide the local somewhere between the root and the read:

```kotlin
@CompositionLocalRoot
@Composable
fun AppRoot(nav: NavController) {
    CompositionLocalProvider(LocalNavController provides nav) {
        HomeScreen()
    }
}
```

or give the local a default, if reading it unprovided is a legitimate state. The message names
the local and the call path that reads it, so the choice between the two is made at the root
with the whole path in view.

## Roots

A root is a composable where the analysis can be sure nothing above provides anything:

- a composable annotated `@CompositionLocalRoot`;
- a `@Preview` (or multipreview) function;
- the composable lambda passed to an entry point: `setContent { }`, `Window { }`,
  `application { }`, `ComposeUIViewController { }`, and the others in
  `compose.compositionLocals.roots`. These are reported on the entry point's name.

Composables that are not roots are never reported. They only contribute what they read and
provide to the roots above them.

## Which locals are required

- Locals whose default factory throws: `compositionLocalOf { error("...") }`,
  `staticCompositionLocalOf { noLocalProvidedFor("LocalX") }`, or any lambda that ends in a
  `throw` or a call returning `Nothing`.
- Locals annotated `@RequiredCompositionLocal`.
- Locals listed in `compose.compositionLocals.required`, which is how a library's local is made
  required without touching the library.

A local with a real default is never required, whatever reads it. Locals listed in
`compose.compositionLocals.platform` are provided at every root by the platform and are never
reported either.

## What counts as providing

- `CompositionLocalProvider(LocalX provides v, LocalY providesDefault w) { ... }`: both locals,
  inside that lambda only. A read in the provider's own arguments happens before the provider
  applies.
- A composable that invokes one of its composable lambda parameters inside a provider provides
  those locals to that parameter: `AppTheme(content)` wrapping `content()` in a provider means
  `AppTheme { LocalPalette.current }` is fine. If the parameter is invoked both inside and
  outside a provider, only what is provided around every invocation counts.
- Inline lambdas (`forEach`, `let`, ...) inherit the scope around them.

## Knowledge base

Library composables are understood through their `@InferredCompositionLocals` metadata when the
library was compiled with Kotrail, and through the project's own entries otherwise:

```properties
compose.compositionLocals.known[com.acme.ui.AppTheme]=content:com.acme.ui.LocalPalette
compose.compositionLocals.known[com.acme.ui.Avatar]=com.acme.ui.LocalImageLoader
compose.compositionLocals.known[com.acme.ui.Plain]=None
```

A value is a comma-separated list of locals the composable reads (`com.acme.ui.LocalImageLoader`)
and locals it provides to a lambda parameter (`content:com.acme.ui.LocalPalette`), or `None`.
An entry replaces what the analysis would otherwise find, including a source body in the same
module; an empty value removes the entry again.

`compose.compositionLocals.roots` lists the entry points (functions whose composable lambda is a
root), `compose.compositionLocals.platform` the locals the platform provides everywhere, and
`compose.compositionLocals.required` the locals to treat as required although their default does
not throw. Every one of them is a comma-separated list of fully qualified names.

## When it stays quiet

- The composable is not a root.
- Every required local read below the root is provided on the way, or listed as
  platform-provided.
- The local has a default and is neither annotated nor listed as required.
- A callee the analysis cannot see into (no source, no metadata, no knowledge base entry) is
  taken to read and provide nothing.

## Cross-module metadata

For every non-private composable that reads or provides a local, the IR extension writes
`@InferredCompositionLocals(reads = [...], provides = [...])`, and for every non-private local
whose default throws it writes `@InferredRequiredCompositionLocal`. Both go through
`metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement`, so consumers read them
with no extra wiring. Every read is recorded, required or not: whether a read is an error is
decided at the root, where the consuming project's settings apply.

## Fixtures

- `compiler-tests/testData/diagnostics/compose/locals/root.kt`, `lambdas.kt`, `entryPoints.kt`,
  `knowledgeBase.kt`
- `compiler-tests/testData/box/compose/locals/inferredMetadataAcrossModules.kt`: compile `lib`
  to class files, satisfy a root in `main` only through the written metadata, and keep an IR
  dump showing both annotations

## Implementation notes

`fir/compose/locals/`: `CompositionLocalService` (session component with the caches and the
scope-tracking walker), `CompositionLocalNames`, and the checkers: the root checker, the
entry-point checker, and a property warm-up that caches which locals throw while initializers
are still available. `ir/compose/locals/InferredCompositionLocalsMetadataWriter.kt` writes the
metadata.

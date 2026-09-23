# Preview coverage (Compose)

**Diagnostics:** `KOTRAIL_COMPOSABLE_NOT_COVERED_BY_PREVIEW`, `KOTRAIL_PREVIEW_COVERAGE_PACKAGE_EMPTY` (error, on the package directive of the compilation's anchor file)
**Key:** `rules.compose.previewCoverage` (**off by default**)
**Settings:** `packages` (default `[]`), `visibility` (default `public`), `excludeNames` (default `[]`)

## What it is for

[Preview required](preview-required.md) wants the preview next to the composable, which is the
right convention for an app. A library cannot always follow it: a preview in `main` ends up in
the published artifact and drags `ui-tooling-preview` into the POM, a KMP library cannot put the
tooling dependency into `commonMain`, and a screenshot-test suite keeps its previews in a test
source set that sees `main` only as a classpath. In all of these the previews live in another
compilation than the composables.

This rule runs in that other compilation, the one that carries the previews, and asks the
question from there: is every public UI composable of the listed packages called by some
`@Preview` function here?

```yaml
# kotrail-screenshotTest.yaml, or the sample module's kotrail.yaml
rules:
  compose.previewRequired: off
  compose.previewCoverage:
    packages: [com.acme.ui, com.acme.ui.cards]
```

The composables are enumerated by package through the compiler's symbol providers, from the
classpath or from this compilation's own files alike, which is why a package is named exactly
rather than by pattern: the classpath cannot be searched by glob. Sub-packages are listed one by
one. A listed package in which this compilation sees no UI composable is reported
(`KOTRAIL_PREVIEW_COVERAGE_PACKAGE_EMPTY`, on the same package directive) rather than counted as
covered, so that a misspelled package, or one whose composables are not visible from here, does
not pass in silence.

## What it rejects

A public, `Unit`-returning, top-level `@Composable` function declared in one of the packages that
no `@Preview` (or multipreview) function of this compilation calls, directly or at any call depth
inside the preview's body. Findings are reported together on one file's package directive, the
first file by name among those that contain a preview, so that a compilation with no preview at
all still fails at a definite place:

```
e: Previews.kt:1:1 [Kotrail] 'com.acme.ui.UserCard' has no preview in this compilation: no @Preview function here calls it. …
```

## A preview compilation next to main

A JVM library that keeps its previews out of the published JAR can give them a compilation of
their own, associated with `main`:

```kotlin
kotlin {
    target.compilations.create("preview") {
        associateWith(target.compilations.getByName("main"))
    }
}

kotrail {
    compilation("preview") {
        configFile = file("kotrail-preview.yaml")
    }
}
```

Seen from `preview`, `main` is class files, which the compiler cannot enumerate by package. So
every compilation the Gradle plugin configures records the UI composables it declares under
`build/kotrail/composables/<target>-<compilation>` (`jvm-main` in a plain Kotlin/JVM project), one JSON-lines file per source file, and a
compilation reads the records of the compilations it is associated with. `previewCoverage` in
`preview` then checks `main`'s composables from `main`'s own record, which knows which of them
draw, since `main` had the bodies. The records are an output of `main`'s compile task and an input
of `preview`'s, so they are always current; nothing has to be switched on in `main`. Outside
Gradle, the compiler plugin's `composablesDir` and `associatedComposablesDir` options do the same.

## Settings

- `packages`: the exact package names to cover. Empty means the rule does nothing.
- `visibility`: `public` (default) or `internal`, which adds internal composables; useful when
  the previews live in a test source set of the same module, where they can be called.
- `excludeNames`: globs over fully qualified names of composables to leave out
  (`com.acme.ui.Legacy*`), for the ones that cannot be previewed.

## How it fits with preview-required

| Where the previews live | Rule |
|---|---|
| Next to the composable (an app) | `previewRequired` (default) |
| Another source set of the same compilation (`androidMain` for `commonMain`) | `previewRequired` off in `main`; `previewCoverage` in the platform compilation, `visibility: internal` if wanted |
| A test or screenshot-test source set, or a `preview` compilation associated with `main` | `previewRequired` off in `main`; `previewCoverage` in that compilation via `test { }` / `compilation(name) { }`; `main`'s composables reach it through its record (above) |
| A sample module | `previewRequired` off in the library; `previewCoverage` in the sample |

## When it stays quiet

- `packages` is empty, or the composable is in a package that is not listed.
- The composable is called from a preview anywhere in this compilation.
- The composable is private, returns a value, is a preview itself, is `expect`, is a class
  member of a module seen from source (only top-level composables are enumerated there; a
  record lists members too), or matches `excludeNames`.
- The composable is in this compilation and draws nothing (see the UI test in
  [preview required](preview-required.md#when-it-stays-quiet), shared through
  `previewRequired.nonUiPackages`). A composable from the classpath is taken as drawing, since
  its body cannot be seen.
- The file is not the anchor: every finding goes to one file.

## Fixtures

`compiler-tests/testData/diagnostics/compose/previewCoverage.kt`, a two-module fixture with the
composables in `lib` and the previews and the rule in `main`.

## Implementation notes

`fir/compose/checkers/ComposablePreviewCoverageChecker.kt`, a `FirFileChecker`. An index per
session, built on first use, walks every source file of the compilation (the `FirProvider`'s
files by package), gathers the callees of every preview body by `CallableId`, and picks the
anchor file. The composables to cover come from
`symbolProvider.symbolNamesProvider.getTopLevelCallableNamesInPackage` followed by
`getTopLevelFunctionSymbols`, filtered to public (or internal) `Unit`-returning composables that
are not previews.

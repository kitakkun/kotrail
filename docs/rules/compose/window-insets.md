# Window insets handling (Compose)

**Diagnostics:**
`WINDOW_INSETS_NOT_HANDLED` (error, on the function name),
`WINDOW_INSETS_HANDLING_UNVERIFIABLE` (warning, on the function name),
`WINDOW_INSETS_HANDLED_TWICE` (warning, on the call)
**Switches:** `rules.compose.windowInsets`, `rules.compose.windowInsetsHandledTwice` (default `true`)
**Artifact:** `annotations` (`com.kitakkun.kotrail.compose.insets`), needed on the
compile classpath of every module the plugin is applied to.

## The problem

Insets bugs are silent. A screen that forgets `safeDrawingPadding()` draws under the status bar;
a screen that applies it twice gets doubled padding. Neither fails a build, and both are easy for
an assistant to introduce when it cannot see the whole call chain.

## The contract

```kotlin
@HandlesWindowInsets(WindowInsetsType.SafeDrawing)
@Composable
fun HomeScreen() { ... }                       // must handle safe-drawing insets on every side

@HandlesWindowInsets(WindowInsetsType.StatusBars, sides = [WindowInsetsSide.Top])
@HandlesWindowInsets(WindowInsetsType.NavigationBars, sides = [WindowInsetsSide.Bottom])
@Composable
fun ChatScreen() { ... }                       // verified through the composables it calls
```

`@HandlesWindowInsets` is a check point. Everything else is inferred silently and recorded as
metadata, so a contract in one module can be satisfied by composables in another.

## API (`annotations`)

```kotlin
enum class WindowInsetsType {
    StatusBars, NavigationBars, CaptionBar, SystemBars,
    DisplayCutout, Ime, SafeDrawing,
    SystemGestures, MandatorySystemGestures, TappableElement, Waterfall,
    SafeGestures, SafeContent,
}

enum class WindowInsetsSide { Top, Bottom, Start, End, Left, Right, Horizontal, Vertical, All }

@Repeatable
annotation class HandlesWindowInsets(
    val type: WindowInsetsType,
    val sides: Array<WindowInsetsSide> = [WindowInsetsSide.All],
)

// Written by the plugin. Never hand-written.
annotation class InferredWindowInsetsHandling(val handled: Array<String>)
```

`sides` defaults to all sides because that is what the vast majority of contracts mean.

## Insets algebra

Primitive types: StatusBars, NavigationBars, CaptionBar, DisplayCutout, Ime, SystemGestures,
MandatorySystemGestures, TappableElement, Waterfall. Composite types expand:

- SystemBars = StatusBars + NavigationBars + CaptionBar
- SafeDrawing = SystemBars + DisplayCutout + Ime
- SafeGestures = SystemGestures + MandatorySystemGestures + TappableElement + Waterfall
- SafeContent = SafeDrawing + SafeGestures

Sides are a 6-bit mask modeled on `WindowInsetsSides`: Top, Bottom, and four horizontal bits
(left in LTR, right in LTR, left in RTL, right in RTL). `Left` = left in both directions,
`Start` = left in LTR + right in RTL, `End` = right in LTR + left in RTL, `Horizontal` = all
four. Handling `Start` therefore does not satisfy a `Left` contract (the RTL left edge stays
open), and the error says so: `displayCutout (left in RTL)`.

Metadata encoding: one string per primitive, `<primitive>:<sides>`, sides being a subset of the
letters `tblrLR` (lower case = LTR, upper case = RTL), e.g. `statusBars:t`, `ime:tblrLR`,
`displayCutout:lR` for `Start`.

## What counts as handling

Walking the body, including every lambda:

- Shorthand modifiers in `androidx.compose.foundation.layout`: `safeDrawingPadding`,
  `safeContentPadding`, `safeGesturesPadding`, `systemBarsPadding`, `statusBarsPadding`,
  `navigationBarsPadding`, `imePadding`, `displayCutoutPadding`, `captionBarPadding`,
  `waterfallPadding`, `systemGesturesPadding`, `mandatorySystemGesturesPadding`.
- `windowInsetsPadding(insets)`, `consumeWindowInsets(insets)`, `insets.asPaddingValues()`: the
  evaluated insets.
- `windowInsetsTopHeight` / `BottomHeight` / `StartWidth` / `EndWidth`: that one side only.
- Calls to other composables: their declared contract, their inferred metadata (other modules),
  the built-in knowledge base, or their source body (same module), in that order.
- Knowledge base: `Scaffold` (SystemBars), `TopAppBar` and variants (SystemBars, top and
  horizontal), `NavigationBar` and `ModalBottomSheet` (SystemBars, bottom and horizontal). A
  `WindowInsets` argument passed to any of them replaces the default.

Insets expressions are evaluated statically: `WindowInsets.<type>` companion properties,
`only(sides)`, `union`, `add`, `exclude`, and `WindowInsetsSides` constants combined with `+`.
Anything else is unknown.

## When each diagnostic fires

| Diagnostic | Condition |
|---|---|
| `WINDOW_INSETS_NOT_HANDLED` | Declared contract minus handled set is non-empty. The message lists the missing primitives and sides. |
| `WINDOW_INSETS_HANDLING_UNVERIFIABLE` | An insets expression could not be evaluated and the contract is not already satisfied by what could be. |
| `WINDOW_INSETS_HANDLED_TWICE` | At a call to composable `G`, a `Modifier` argument applies inset padding whose set intersects what `G` handles. |

Composables without a contract are never reported. Members of classes and objects, and local
composables, are analyzed like any other callee.

## Cross-module metadata

For every non-private composable without a contract, the IR extension writes
`@InferredWindowInsetsHandling(handled = [...])` through
`metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement`. On JVM the annotation
lands in the class file, which is what the FIR deserializer reads, so consumers see it with no
extra wiring.

FIR bodies are released after Fir2Ir (verified on Kotlin 2.4.0), so the analysis runs during the
FIR checker phase: the contract checker calls the analysis for every composable, contract or not,
which fills a per-session cache. The IR writer only reads that cache.

In modules that also apply the Compose compiler plugin, register Kotrail through
`kotlinCompilerPluginClasspath(project(":plugin"))` rather than a hand-written `-Xplugin=` so
both plugins are passed by the Kotlin Gradle plugin the same way.

## Fixtures

- `compiler-tests/testData/diagnostics/compose/insets/contract.kt`, `sides.kt`, `nested.kt`,
  `unverifiable.kt`, `handledTwice.kt`
- `compiler-tests/testData/box/compose/insets/inferredMetadataAcrossModules.kt`,
  `inferredMetadataOnMembers.kt`: compile `lib` to class files, satisfy contracts in `main`
  only through the written metadata, and keep IR golden dumps showing the annotation
- `sample-compose/`: real Compose Multiplatform modules; `app/violations/` triggers each diagnostic

## Implementation notes

`fir/compose/insets/`: `WindowInsetsHandlingService` (session component with the cache),
`WindowInsetsExpressionEvaluator`, `WindowInsetsNames` (names and knowledge base), and the two
checkers. `compose/insets/InsetsModel.kt` holds the algebra shared with
`ir/compose/insets/InferredWindowInsetsMetadataWriter.kt`.

Not covered yet: a user-extensible knowledge base for library composables, and IDE highlighting
(which needs the Gradle plugin).

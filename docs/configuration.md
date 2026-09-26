# Configuration

Every rule is on, at error severity, with no configuration at all, except the few whose page
says "off by default" ([no hardcoded string](rules/compose/no-hardcoded-string.md),
[no unstable parameter](rules/compose/no-unstable-parameter.md),
[JvmSynthetic for internal](rules/jvm-synthetic-for-internal.md)). Settings live in a
`kotrail.yaml` that the [Gradle plugin](gradle-plugin.md) points at, and come from three sources;
later ones win:

1. built-in defaults,
2. the YAML files passed through the `configFile` plugin option, in the order they are passed,
   merged key by key,
3. individual plugin options, which name the same keys as dotted paths.

Unknown keys or malformed values fail the build, with the file, line, and column. A typo never
silently disables a rule. The parsers are strict on purpose: a value that is rejected today may
be given a meaning in a later version, so nothing that builds now changes meaning then.

```yaml
# kotrail.yaml
note: Conventions live in CONTRIBUTING.md.
exclude: package(com.acme.generated.*) || file(*Generated.kt)

rules:
  preferValueClass: off
  commentLength: warning

  functionLength:
    maxLines: 60
    exclude: name(main)

  forbiddenCall:
    functions:
      - kotlin.io.println
      - kotlinx.coroutines.GlobalScope.launch
    calls:
      blocking: fqn(kotlinx.coroutines.runBlocking)

  requiredAnnotation:
    policies:
      screens:
        where: composable && name(*Screen)
        annotation: com.acme.navigation.Screen
```

Add `# yaml-language-server: $schema=https://kitakkun.github.io/kotrail/kotrail.schema.json` as
the first line and IntelliJ and VS Code complete keys and values and flag unknown ones; the
schema is generated from the same table the plugin validates against.

## The shape of the file

| Key | Meaning |
|---|---|
| `enabled` | `false` turns the whole plugin off. |
| `severity` | `error` (default) or `warning`: the severity of every rule that does not set its own. `severity: warning` is the one line that turns a first run on an existing codebase into a measurement. |
| `note` | Text appended to every Kotrail message. See [Project notes](#project-notes). |
| `exclude` | A predicate over locations; matching diagnostics of every rule are dropped. See [Excluding by pattern](#excluding-by-pattern). |
| `fix` | `false` stops every rule from recording fixes for `kotrailFix`; the diagnostics are still reported. A rule's own `fix` wins. |
| `predicates.<name>` | A predicate under a name of the project's own (`screen: composable && name(*Screen)`), which `exclude`, the policy rules and `forbiddenCall` then use by that name. See [Named predicates](#named-predicates). |
| `generated.paths` | Globs over source file paths (`/` separated) of generated code, which every rule skips. Default `[*/build/generated/*]`; replaces the default. See [Generated code](#generated-code). |
| `generated.annotations` | Fully qualified annotations that mark a file (`@file:Generated`) or a declaration as generated, which every rule skips. Default: `javax.annotation.processing.Generated`, `javax.annotation.Generated`, `jakarta.annotation.Generated`; replaces the default list. |
| `test.annotations` | Fully qualified annotations that mark a function as a test; shared by the test rules and the `test` predicate. Replaces the default list. |
| `rules.<rule>` | One entry per rule, named by the rule's full key (`functionLength`, `compose.nesting`, `test.naming`), as a shorthand or a mapping. |

A rule entry is either a **shorthand**, for the common case,

```yaml
rules:
  preferValueClass: off        # or on
  commentLength: warning       # on, at that severity
  noNotNullAssertion: error
```

or a **mapping** of the five keys every rule has and the rule's own settings:

```yaml
rules:
  functionLength:
    enabled: true              # the switch
    severity: error            # error or warning
    note: See ADR-014.         # appended to this rule's messages; wins over the top-level note
    exclude: name(main)        # locations this rule skips
    fix: false                 # report, but record no fix for kotrailFix
    maxLines: 60               # the rule's settings, listed on its page
    maxComposableLines: 100
```

Rule keys are never split on dots: `compose.nesting` is one key under `rules`, and a nested
`compose:` group is an error. Diagnostics without a switch of their own
(`compose.windowInsetsUnverifiable`) accept `error` and `warning` but not `on` or `off`.

## Rules and their settings

Every rule takes `enabled`, `severity`, `note`, `exclude`, and `fix`. The settings below are the rule's
own; a rule not listed has none.

| Rule | Setting | Default | Meaning |
|---|---|---|---|
| `narrowModelParameters` | `maxUnusedProperties` | `3` | How many properties of a data-class parameter may stay unread. |
| | `scope` | `composables` | `composables` or `all`. |
| `preferFunctionReferences` | `forms` | `[topLevel, bound, typeQualified]` | Which reference shapes the rule asks for; drop `typeQualified` to keep `{ it.readText() }`. |
| `commentLength` | `maxLines` | `5` | Longest allowed block comment or run of consecutive `//` lines; `0` for unlimited. |
| | `maxKDocLines` | `0` | Longest allowed KDoc; `0` for unlimited. |
| `noFqnReferences` | `allow` | `[]` | Package prefixes whose members may be referenced fully qualified. |
| `forbiddenCall` | `functions` | `[]` | Fully qualified callables that must not be called. |
| | `calls` | `{}` | Named call predicates; matching calls are reported under the entry's name. See [Forbidden call](rules/forbidden-call.md). |
| `namedArgumentsForRepeatedTypes` | `minArguments` | `3` | How many positional arguments of one type require names. |
| `mustBeSerializable` | `requiredFor` | `[androidx.compose.runtime.saveable.rememberSerializable]` | Callables whose type arguments must be serializable. Replaces the default list. |
| `fileLength` | `maxLines` | `500` | Most lines of code a file may have; blank, brace-only, comment, `package` and `import` lines do not count. `0` for unlimited. |
| | `maxTopLevelDeclarations` | `15` | Most distinct top-level names a file may declare: classes, functions, public properties and type aliases, overloads once; `@Preview` functions, private properties, `actual` declarations and private implementations of a same-file interface do not count. `0` for unlimited. |
| `functionLength` | `maxLines` | `50` | Most lines of code a function body may have; `0` for unlimited. |
| | `maxComposableLines` | `80` | The same limit for `@Composable` functions. |
| `nullChainLength` | `maxElvis` | `2` | Maximum `?:` fallbacks in one expression, a trailing `?: return` / `?: throw` not counted; `0` disables. |
| | `maxSafeCalls` | `0` | Maximum `?.` along one receiver chain; `0` disables. |
| `implicitReceivers` | `qualifyAmbiguous` | `true` | Whether a bare name that two implicit receivers in scope could supply is reported. |
| | `maxDepth` | `0` | Maximum implicit receivers in scope at once; `0` disables. |
| `sealedWhenBranchStyle` | `style` | `is` | How an object case of a sealed type is written in a `when` branch: `is` (`is Cancel ->`) or `object` (`Cancel ->`). |
| `preferIdiom` | `disabled` | `[]` | Idioms not asked for: `emptiness`, `negation`, `nullOrEmpty`, `chain`, `elvis`. |
| | `chains` | `[]` | The project's own chain idioms, each `<inner fqn> then <outer fqn> -> <replacement fqn>`. |
| | `calls` | `[]` | The project's own call idioms, each `<fqn>(<literal>) -> <replacement fqn>`. |
| `native.objcThrows` | `packages` | `[]` | Package globs of the API the framework exports to Swift; when set, only public functions in these packages are checked. Empty: every public function of an Apple compilation. |
| `nativeAllocationInLoop` | `types` | `[org.jetbrains.skia.impl.Managed, java.awt.image.VolatileImage]` | Types (subtypes included) whose instances hold native memory that only a cleaner frees. Replaces the default list. |
| | `factories` | `[java.nio.ByteBuffer.allocateDirect]` | Factory functions that return such an instance. Replaces the default list. |
| | `callbacks` | `collect`, `onEach`, `withFrameNanos`, `repeat`, `forEach`, ... | Functions whose lambda runs once per item or frame, counted like a loop body. Replaces the default list. |
| `weakOnlyReference` | `types` | `[java.lang.ref.WeakReference, java.lang.ref.SoftReference, kotlin.native.ref.WeakReference]` | Weak or soft reference types (subtypes included). Replaces the default list. |
| `catchTooBroad` | `types` | `kotlin.Throwable`, `kotlin.Exception`, `kotlin.RuntimeException` and their `java.lang` classes, `java.lang.Error` | Exception types a catch clause must not name. Replaces the default list. |
| | `report` | `swallowed` | `swallowed`: only clauses that let the failure go no further than a log line; `all`: every broad clause. |
| | `loggers` | `println`, `printStackTrace`, `android.util.Log.*`, SLF4J, kotlin-logging, Kermit, Timber, IntelliJ `Logger.*` | Functions that only log; a failure handed to them alone counts as swallowed. Replaces the default list. |
| `unretained` | `annotations` | `[com.kitakkun.kotrail.lifetime.Unretained]` | Annotations that mark a parameter as not to be retained. Replaces the default list. |
| | `weakTypes` | `[java.lang.ref.WeakReference, ...]` | Weak reference types through which such a parameter may be kept. Replaces the default list. |
| `requiredSupertype` | `policies` | `{}` | Named policies, `where -> supertype` or a mapping with `where` and `supertype`; matching classes must extend or implement it. |
| `dependencyRules` | `policies` | `{}` | Named policies, each a mapping with `from` (a package glob), `deny` (package globs) and optionally `allow`. |
| `delayForCompletion` | `delays` | `[kotlinx.coroutines.delay, java.lang.Thread.sleep, android.os.SystemClock.sleep]` | Functions that wait a fixed time. Replaces the default list. |
| | `starters` | `launch`, `async`, `launchIn`, `Thread.start`, `thread`, `Handler.post*`, `Executor.execute`, `ExecutorService.submit`, `Timer.schedule` | Functions that start work that outlives the call. Replaces the default list. |
| `unloadableCode` (off by default) | `registrations` | JVM, AWT and IntelliJ hooks (see the rule page) | Globs over fully qualified functions that register something with the platform for the rest of its life; a call without a disposable argument is reported. Replaces the default list. |
| | `disposableTypes` | `[com.intellij.openapi.Disposable]` | Fully qualified types an argument of which scopes a registration to a lifetime. Replaces the default list. |
| `narrowLocalScope` | `maxDistance` | `5` | Lines allowed between a local's declaration and the statement that first uses it; `0` switches the distance check off. |
| `liveVariableBudget` | `max` | `7` | Variables (locals and parameters) that may be live at one statement of a function; `0` disables. |
| `noDataClassInPublicApi` | `scope` | `explicitApi` | `explicitApi` applies the rule only to modules compiled with explicit API mode; `all` everywhere. |
| `visibilityPolicy` | `private` | | A predicate; matching declarations must be `private`. |
| | `internal` | | A predicate; matching declarations must be `internal` or `private`. |
| `requiredAnnotation` | `policies` | `{}` | Named policies, each `where` (a predicate) and `annotation` (a fully qualified name). See [Required annotation](rules/required-annotation.md). |
| `compose.windowInsets` | `known` | built-in Material 3 entries | What a library composable handles, keyed by its fully qualified name: `Type` or `Type:Side+Side` entries, or `none`. See [Window insets](rules/compose/window-insets.md#knowledge-base). |
| `compose.compositionLocals` | `platform` | `[]` | Locals the platform provides at every root; reads of these are never reported. |
| | `required` | `[]` | Locals to treat as required although their default does not throw. |
| | `roots` | `[setContent, Window, application, ...]` | Functions whose composable lambda is a root of composition. Replaces the default list. |
| | `known` | `{}` | What a library composable reads and provides, keyed by its fully qualified name. See [Composition locals](rules/compose/composition-locals.md#knowledge-base). |
| `compose.nesting` | `maxDepth` | `5` | Nesting limit for composable calls; `0` disables the rule. |
| `compose.noTrailingCallback` | `allowedPackages` | `[androidx.compose.runtime]` | Packages whose composables may still take a callback as a trailing lambda. |
| `compose.previewRequired` | `scope` | `internal` | Which UI composables need a `@Preview` in their file: `public`, `internal` (public and internal), or `all`. |
| | `nonUiPackages` | `[androidx.compose.runtime]` | Packages whose composables emit nothing themselves (effects, providers); calling them does not make a composable a UI one. Shared with `previewCoverage`. Replaces the default list. |
| `compose.previewCoverage` (off by default) | `packages` | `[]` | Exact package names whose top-level UI composables must be called by a `@Preview` somewhere in this compilation. |
| | `visibility` | `public` | Which composables of those packages count: `public`, or `internal` (public and internal). |
| | `excludeNames` | `[]` | Globs over fully qualified composable names to leave out. |
| `compose.previewParameter` | `minPreviews` | `2` | How many `@Preview` functions of one file must build the same model inline before they are reported; `1` reports every one. |
| `compose.rememberKeys` | `functions` | `remember`, `rememberSaveable`, `LaunchedEffect`, `DisposableEffect`, `produceState` | Functions whose trailing lambda is keyed by their other arguments. Replaces the default list. |
| `compose.noCallbackInModel` | `allowComposableSlots` | `true` | Whether a `@Composable` function-typed property (a content slot such as a table column's cell renderer) in a model handed to a UI composable is allowed; other function types are always reported. |
| `compose.composablesPerFile` | `max` | `3` | Maximum non-private UI composables in one file, previews excluded; `0` disables. |
| | `countOverloadsSeparately` | `false` | Whether overloads of one composable name count one each; by default they count as one component. |
| `compose.noGlobalMutableState` | `handlerWrites` | `true` | Whether an assignment to a global `var` from a composable's event handler or effect is reported; assignments during composition always are. |
| `compose.noSideEffectInComposition` | `types` | `[kotlinx.coroutines.Job, kotlinx.coroutines.Deferred]` | Declared return types that mark a call as starting work. Replaces the default list. |
| | `functions` | `[]` | Fully qualified functions reported by name whatever they return. |
| `compose.noUnstableParameter` (experimental, off by default) | `stableTypes` | `[]` | Types the project declares stable, as fully qualified names with `*` / `**` wildcards and an optional `<*,_>` mask, like a Compose stability configuration file. |
| `compose.noHardcodedString` (off by default) | `parameters` | `[text, label, title, placeholder, contentDescription, message]` | Composable parameters that must not receive a string literal. Replaces the default list. |
| `test.naming` | `style` | `backticked` | `backticked` for a sentence name, `identifier` for targets that reject spaces (Android instrumented tests). |
| | `minWords` | `3` | Words a backticked test name must have; `2` requires backticks only, `1` accepts any name. |
| `test.mustAssert` | `assertions` | kotlin.test, JUnit, assertk, kotest, Truth, Mokkery/MockK/Mockito verify, and `*.assert*`, `*.verify*`, `*.expect*`, `*.should*`, `*.waitUntil*`, `*.captureRoboImage*`, `withTimeout` | Globs over functions that assert or verify; a test calling none, directly or through helpers, is reported. Replaces the default list. |
| `test.noSleep` | `functions` | `[java.lang.Thread.sleep, android.os.SystemClock.sleep, java.util.concurrent.TimeUnit.sleep]` | Functions that wait real time; reported anywhere in a test. Replaces the default list. |
| | `virtualTime` | `[kotlinx.coroutines.test.runTest]` | Functions whose lambda runs on virtual time, where `delay` is free. Replaces the default list. |

The rule keys, for `rules:` and for `@Suppress` names, are on the [rules index](rules/README.md).

## Severity

Kotrail's stance is that a rule violation is an error and that genuine exceptions are marked
with `@Suppress` at the spot. Severity exists for the other case: a rule that produces more
findings in an existing codebase than can be fixed at once, or a team that wants to adopt a rule
gradually.

```yaml
rules:
  noPassThroughReturn: warning
  compose.windowInsetsHandledTwice: error    # a warning by default
```

A diagnostic reported at a non-default severity carries a suffixed name, following the
compiler's own convention for deprecations: demoting `noPassThroughReturn` yields
`KOTRAIL_PASS_THROUGH_RETURN_WARNING`, promoting `compose.windowInsetsHandledTwice` yields
`KOTRAIL_WINDOW_INSETS_HANDLED_TWICE_ERROR`. `@Suppress` accepts either the name in effect or the
base name, so a suppression written before the severity changed keeps working.

A Kotrail warning is not promoted by `-Werror` (`allWarningsAsErrors = true` in Gradle). It is a
decision the project wrote down, whether as `severity: warning` or as a rule that defaults to
warning, and a project that treats the compiler's warnings as errors would otherwise lose the
gradual path entirely. Under the hood the diagnostic is reported at the compiler's fixed-warning
level, the same one `-Xwarning-level=<name>:warning` produces, and that option still applies to
Kotrail diagnostics by their name in effect (`-Xwarning-level=KOTRAIL_PASS_THROUGH_RETURN_WARNING:error`
promotes one back). To have `-Werror` fail on a Kotrail finding, set the rule to `error`.

## Project notes

A rule's message says what it found and what to write instead. It does not know *why your project
decided this*, and that is often what a reader, or an assistant reading the compiler output, needs
in order to make the right change rather than the smallest one.

```yaml
note: Conventions live in CONTRIBUTING.md.
rules:
  preferExplicitBackingField:
    note: ViewModels expose StateFlow directly here; see ADR-014.
```

```
e: Cases.kt:3:9 [Kotrail] This property is exposed through the backing property '_items'. Declare
   it with an explicit backing field instead. (KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD) ViewModels
   expose StateFlow directly here; see ADR-014.
```

The note is **appended** to the built-in message, never substituted for it, so the rewrite a rule
asks for cannot be lost by configuring one. A rule's own `note` replaces the top-level `note` for
that rule; leading and trailing whitespace is trimmed and a separating space is added.

## Generated code

Code that a tool wrote (KSP and kapt output, Compose resources, SQLDelight, protobuf) is not
anyone's to fix, so every rule skips it. A file is generated when its path matches one of
`generated.paths` (`*/build/generated/*` by default, which is where Gradle plugins put their
output) or when it, or a declaration enclosing the location, carries one of
`generated.annotations`. Generated code is skipped entirely: nothing is reported in it, no fix is
recorded for it, and a rule that records findings for another compilation (unloadable code)
records nothing from it. A generator that writes somewhere else is covered by adding its path:

```yaml
generated:
  paths: ["*/build/generated/*", "*/src/*/generatedKotlin/*"]   # quoted: a bare * starts a YAML alias
```

## Excluding by pattern

`@Suppress` handles one occurrence. For a structural carve-out — a generated package, every
extension of one type, everything inside classes named a certain way — write a predicate over
the location a diagnostic would be reported at:

```yaml
exclude: package(com.acme.generated.*) || file(*Generated.kt)
rules:
  noPassThroughFunction:
    exclude: extension(kotlin.String) || annotated(com.acme.PublicApi)
  functionLength:
    exclude: test || name(main)
  noNotNullAssertion:
    exclude: class(*Migration) && !annotated(com.acme.Reviewed)
```

The top-level `exclude` applies to every rule and a rule's own `exclude` to that rule; a
diagnostic is dropped when either matches. Predicates combine with `&&`, `||`, `!` and
parentheses, and are checked when the configuration is read, so a typo fails the build with its
position. A predicate that starts with `!` has to be quoted, since YAML would read it as a tag:
`exclude: "!annotated(com.acme.Reviewed)"`.

| Predicate | True when |
|---|---|
| `package(glob)`, `file(glob)` | the file's package or file name matches |
| `name(glob)` | the innermost enclosing declaration (function, property, class) has that name |
| `path(glob)` | the file's full path, with `/` separators, matches (`*/src/commonMain/*`) |
| `class(glob)` | any enclosing class, or the declaration itself if it is a class, has that name |
| `function`, `property`, `class` | the innermost declaration is a function, a property, or a class |
| `annotated(fqn)` | the innermost declaration or any enclosing one carries that annotation |
| `extension`, `extension(fqn)` | the innermost declaration is an extension, of that receiver type |
| `context`, `context(fqn)` | the innermost declaration has a context parameter, of that type |
| `visibility(public\|internal\|protected\|private)` | the innermost declaration has that visibility |
| `override`, `suspend`, `inline` | the innermost declaration has that modifier |
| `composable`, `test` | the innermost function is a `@Composable`, or a test by `test.annotations` |

Globs match the whole value; `*` stands for any run of characters (dots included, so
`com.acme.*` covers nested packages) and `?` for one.

The predicates describe **where** a diagnostic is, never **what** it found. "Skip calls to
functions from package X" is a rule-specific question, and lives in that rule's own settings.

## Named predicates

The same condition tends to recur across policies: what a screen is, what a preview is, what
counts as blocking. The top-level `predicates` mapping names such conditions once, and every
place that takes a predicate (`exclude`, `visibilityPolicy`, `requiredAnnotation`,
`requiredSupertype`, `forbiddenCall`) then uses the name as if it were a built-in:

```yaml
predicates:
  screen: composable && name(*Screen)
  preview: composable && annotated(androidx.compose.ui.tooling.preview.Preview)
  viewModel: class && name(*ViewModel)
  blocking: fqn(kotlinx.coroutines.runBlocking)
rules:
  visibilityPolicy:
    private: preview
  requiredAnnotation:
    policies:
      screens: screen -> com.acme.ScreenRoute
  requiredSupertype:
    policies:
      viewModels: viewModel -> com.acme.BaseViewModel
  forbiddenCall:
    calls:
      noBlocking: blocking
  functionLength:
    exclude: preview
```

A name is letters only and stands for its text, substituted where it is used: a name may refer
to other names, and the built-in `composable` is the same kind of thing, only predefined. A
named predicate is parsed in the language of the place that uses it, so `screen` fits a
declaration predicate and `blocking` a call predicate; using one where its atoms do not exist is
an error at that place. There are no parameters: `screen(Home)` is an error, since a predicate
with arguments would be a language of its own.

Three things are refused when the configuration is read, each with its position: a name that is
a built-in predicate (`composable`, `name`, `fqn`, ...), which would silently change every
predicate that uses it; a cycle (`a: b`, `b: a`), reported as `a -> b -> a`; and a second
definition of a name in a later configuration layer. A vocabulary is declared once, in the
project's root file or a convention plugin, and shared by every module through
[layering](#layering-and-test-source-sets); a layer may unset a name with `~` but not redefine
it. Wherever a predicate is rendered, a name shows with its expansion, as
`screen (= composable && name(*Screen))`, so the indirection never hides what matched.

## Layering and test source sets

The compiler sees one compilation at a time, so per-source-set settings are a Gradle concern. The
Gradle plugin has a block for it, matching every compilation whose name contains `test`, plus one
for a single compilation by name:

```kotlin
kotrail {
    configFile = layout.projectDirectory.file("kotrail.yaml")
    test {
        configFile = layout.projectDirectory.file("kotrail-test.yaml")
    }
    compilation("androidTest") {
        configFile = layout.projectDirectory.file("kotrail-androidTest.yaml")
    }
}
```

Files are **layered, not replaced**: a compilation is given the project's file first and then the
file of every override that matches it, and the trees are merged key by key. Mappings merge
recursively; a scalar or a list replaces what was there; `~` (or `null`, or nothing after the
colon) takes a key away, so that the built-in default applies again. An override's file therefore
lists only what it changes:

```yaml
# kotrail-test.yaml: everything in kotrail.yaml still applies
exclude: ~                       # the project-wide predicate is gone in tests
rules:
  preferExplicitBackingField: off
  compose.nesting: off
  functionLength:
    maxLines: 120                # maxComposableLines and exclude still apply
  requiredAnnotation:
    policies:
      screens: ~                 # this policy is dropped; the others stay
  compose.windowInsets:
    known:
      androidx.compose.material3.Scaffold: ~   # the built-in entry applies again
```

A list is replaced whole: `functions: [a]` in a later file is the whole list, and `roots: []` is
an empty list rather than the default. Use `~` for the default.

Turning `compose.windowInsets` off in a *main* source set also stops the inferred insets metadata
from being written, so other modules can no longer verify calls into it. Turning it off in tests
has no such effect.

## Plugin options

A build that wires the compiler plugin by hand passes the file, and may patch single entries with
plugin options. An option is named by the key's dotted path, with the rule's own dots kept, and
its value is read the way the file would read it:

```kotlin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

tasks.withType<KotlinCompile>().configureEach {
    val file = if (name.contains("Test")) "kotrail-test.yaml" else "kotrail.yaml"
    compilerOptions.freeCompilerArgs.addAll(
        "-P", "plugin:com.kitakkun.kotrail:configFile=${projectDir.resolve(file)}",
        "-P", "plugin:com.kitakkun.kotrail:rules.compose.nesting.maxDepth=6",   // patches the file
        "-P", "plugin:com.kitakkun.kotrail:rules.preferValueClass=off",
    )
}
```

| Option | Value |
|---|---|
| `enabled`, `note`, `exclude`, `test.annotations`, `generated.paths`, `generated.annotations` | as in the file; a list is comma separated |
| `rules.<rule>` | `off`, `on`, `error`, or `warning` |
| `rules.<rule>.enabled` / `.severity` / `.note` / `.exclude` | as in the file |
| `rules.<rule>.<setting>` | as in the file; a list is comma separated |
| `rules.<rule>.<map setting>` | `<name>=<value>`, one entry per occurrence; `<name>=` drops the entry |

An empty value unsets the key, like `~` in the file. The compiler splits a `-P` value on commas,
so a list that needs more than one item belongs in the file.

## Suppressing a single occurrence

Every Kotrail diagnostic can be suppressed by name, per declaration or per file, exactly like a
built-in one. The names all start with `KOTRAIL_`, so a suppression says where the rule comes
from and cannot meet a compiler diagnostic of the same name. Every message ends with the name to
put in the suppression:

```kotlin
@Suppress("KOTRAIL_COMPOSABLE_NESTING_TOO_DEEP")
@Composable
fun LegacyScreen() { ... }
```

## In the compiler tests

Every rule is switched off before a fixture is compiled, and the fixture opts in to the ones it
exercises through a directive on its first lines. The values are plugin options and go through
the same command-line processor as real builds:

```kotlin
// KOTRAIL_CONFIG: rules.compose.nesting=on, rules.compose.nesting.maxDepth=2
```

Opting in keeps each fixture about one rule, and means a newly added rule cannot start reporting
across fixtures that were written for something else. It is the opposite of the default a real
build gets, where every rule is on.

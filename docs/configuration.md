# Configuration

Every rule is on, at error severity, with no configuration at all. Settings come from three
sources; later ones win:

1. built-in defaults,
2. `.properties` files passed through the `configFile` plugin option, in the order they are
   passed, so a later file overrides the entries of an earlier one,
3. individual plugin options.

Unknown keys or malformed values in a file fail the build. A typo never silently disables a
rule.

## Keys

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Turns the whole plugin off when `false`. |
| `note` | (empty) | Text appended to every Kotrail message. See [Project notes](#project-notes). |
| `note.<rule>` | (empty) | Text appended to one rule's messages, overriding `note`. |
| `rules.preferExplicitBackingField` | `true` | [Prefer explicit backing fields](rules/prefer-explicit-backing-field.md). |
| `rules.narrowModelParameters` | `true` | [Narrow model parameters](rules/narrow-model-parameters.md). |
| `narrowModelParameters.maxUnusedProperties` | `3` | How many properties of a data-class parameter may stay unread. |
| `narrowModelParameters.scope` | `composables` | `composables` or `all`. |
| `rules.noPassThroughReturn` | `true` | [No pass-through return](rules/no-pass-through-return.md). |
| `rules.preferFunctionReferences` | `true` | [Prefer function references](rules/prefer-function-references.md). |
| `preferFunctionReferences.forms` | `topLevel,bound,typeQualified` | Which reference shapes the rule asks for; drop `typeQualified` to keep `{ it.readText() }`. |
| `rules.commentLength` | `true` | [Comment length](rules/comment-length.md). |
| `comments.maxLines` | `5` | Longest allowed block comment or run of consecutive `//` lines; `0` for unlimited. |
| `comments.maxKDocLines` | `0` | Longest allowed KDoc; `0` for unlimited. |
| `rules.noFqnReferences` | `true` | [No FQN references](rules/no-fqn-references.md). |
| `noFqnReferences.allow` | (empty) | Package prefixes whose members may be referenced fully qualified. |
| `rules.noRedundantElse` | `true` | [No redundant else](rules/no-redundant-else.md). |
| `rules.preferValueClass` | `true` | [Prefer value class](rules/prefer-value-class.md). |
| `rules.forbiddenCall` | `true` | [Forbidden call](rules/forbidden-call.md). |
| `forbiddenCall.functions` | (empty) | Fully qualified callables that must not be called. |
| `rules.noNotNullAssertion` | `true` | [No not-null assertion](rules/no-not-null-assertion.md). |
| `rules.noSwallowedCancellation` | `true` | [No swallowed cancellation](rules/no-swallowed-cancellation.md). |
| `rules.noIgnoredException` | `true` | [No ignored exception](rules/no-ignored-exception.md). |
| `rules.preferExpressionBody` | `true` | [Prefer expression body](rules/prefer-expression-body.md). |
| `rules.noMutableCollectionInPublicApi` | `true` | [No mutable collection in public API](rules/no-mutable-collection-in-public-api.md). |
| `rules.namedArgumentsForRepeatedTypes` | `true` | [Named arguments for repeated types](rules/named-arguments-for-repeated-types.md). |
| `namedArguments.minSameTypeArguments` | `3` | How many positional arguments of one type require names. |
| `rules.mustBeSerializable` | `true` | [Must be serializable](rules/must-be-serializable.md). |
| `serialization.requiredFor` | `androidx.compose.runtime.saveable.rememberSerializable` | Callables whose type arguments must be serializable, in addition to `@MustBeSerializable` contracts. |
| `rules.noUnimplemented` | `true` | [No unimplemented code](rules/no-unimplemented.md). |
| `rules.preconditions` | `true` | [Preconditions](rules/preconditions.md): call-site check and inferred metadata. |
| `rules.functionLength` | `true` | [Function length](rules/function-length.md). |
| `functionLength.maxLines` | `50` | Most lines of code a function body may have; `0` for unlimited. |
| `functionLength.maxComposableLines` | `80` | The same limit for `@Composable` functions. |
| `rules.compose.windowInsets` | `true` | [Window insets](rules/compose/window-insets.md): contract check and inferred metadata. |
| `rules.compose.windowInsetsHandledTwice` | `true` | Doubled inset padding warning (needs `rules.compose.windowInsets`). |
| `rules.compose.stateDelegation` | `true` | [State delegation](rules/compose/state-delegation.md). |
| `rules.compose.nesting` | `true` | [Nesting limit](rules/compose/nesting.md). |
| `compose.maxNesting` | `5` | Nesting limit for composable calls; `0` disables the rule. |
| `rules.compose.noTrailingCallback` | `true` | [No trailing callback](rules/compose/no-trailing-callback.md). |
| `rules.compose.naming` | `true` | [Composable naming](rules/compose/naming.md). |
| `rules.compose.modifierParameter` | `true` | [Modifier parameter](rules/compose/modifier-parameter.md). |
| `rules.compose.namedCallbackArguments` | `true` | [Named callback arguments](rules/compose/named-callback-arguments.md). |
| `compose.trailingLambdaAllowedPackages` | `androidx.compose.runtime` | Packages whose composables may still take a callback as a trailing lambda. |
| `rules.compose.previewRequired` | `true` | [Preview required](rules/compose/preview-required.md). |
| `compose.preview.requireFor` | `internal` | Which UI composables need a `@Preview` in their file: `public`, `internal` (public and internal), or `all`. |
| `rules.compose.composablesPerFile` | `true` | [Composables per file](rules/compose/composables-per-file.md). |
| `compose.maxComposablesPerFile` | `3` | Maximum non-private UI composables in one file, previews excluded; `0` disables. |
| `rules.test.naming` | `true` | [Test naming](rules/test/naming.md). |
| `test.annotations` | `kotlin.test` and JUnit 4/5 test annotations | Fully qualified annotations that mark a function as a test. Replaces the default list. |
| `test.naming.style` | `backticked` | `backticked` for a sentence name, `identifier` for targets that reject spaces (Android instrumented tests). |
| `test.naming.minWords` | `3` | Words a backticked test name must have; `2` requires backticks only, `1` accepts any name. |

## Severity

Kotrail's stance is that a rule violation is an error and that genuine exceptions are marked
with `@Suppress` at the spot. Severity keys exist for the other case: a rule that produces
more findings in an existing codebase than can be fixed at once, or a team that wants to
adopt a rule gradually.

| Key | Default |
|---|---|
| `severity.preferExplicitBackingField` | `error` |
| `severity.narrowModelParameters` | `error` |
| `severity.noPassThroughReturn` | `error` |
| `severity.preferFunctionReferences` | `error` |
| `severity.commentLength` | `error` |
| `severity.noFqnReferences` | `error` |
| `severity.noRedundantElse` | `error` |
| `severity.preferValueClass` | `error` |
| `severity.forbiddenCall` | `error` |
| `severity.noNotNullAssertion` | `error` |
| `severity.noSwallowedCancellation` | `error` |
| `severity.noIgnoredException` | `error` |
| `severity.preferExpressionBody` | `error` |
| `severity.noMutableCollectionInPublicApi` | `error` |
| `severity.namedArgumentsForRepeatedTypes` | `error` |
| `severity.mustBeSerializable` | `error` |
| `severity.noUnimplemented` | `error` |
| `severity.preconditions` | `error` |
| `severity.functionLength` | `error` |
| `severity.compose.windowInsets` | `error` |
| `severity.compose.windowInsetsUnverifiable` | `warning` |
| `severity.compose.windowInsetsHandledTwice` | `warning` |
| `severity.compose.stateDelegation` | `error` |
| `severity.compose.nesting` | `error` |
| `severity.compose.noTrailingCallback` | `error` |
| `severity.compose.naming` | `error` |
| `severity.compose.modifierParameter` | `error` |
| `severity.compose.namedCallbackArguments` | `error` |
| `severity.compose.previewRequired` | `error` |
| `severity.compose.composablesPerFile` | `error` |
| `severity.test.naming` | `error` |

Values are `error` or `warning`. A diagnostic reported at a non-default severity carries a
suffixed name, following the compiler's own convention for deprecations: demoting
`PASS_THROUGH_RETURN` yields `PASS_THROUGH_RETURN_WARNING`, promoting
`WINDOW_INSETS_HANDLED_TWICE` yields `WINDOW_INSETS_HANDLED_TWICE_ERROR`. `@Suppress` uses
whichever name is in effect.

## Project notes

A rule's message says what it found and what to write instead. It does not know *why your project
decided this*, and that is often what a reader, or an assistant reading the compiler output, needs
in order to make the right change rather than the smallest one.

```properties
note=Conventions live in CONTRIBUTING.md.
note.preferExplicitBackingField=ViewModels expose StateFlow directly here; see ADR-014.
```

```
e: Cases.kt:3:9 [Kotrail] This property is exposed through the backing property '_items'. Declare
   it with an explicit backing field instead. ViewModels expose StateFlow directly here; see ADR-014.
```

The note is **appended** to the built-in message, never substituted for it, so the rewrite a rule
asks for cannot be lost by configuring one. A rule's own `note.<rule>` replaces the project-wide
`note` for that rule; leading and trailing whitespace is trimmed and a separating space is added.

## Passing settings

Rules are configured in a properties file, and the [Gradle plugin](gradle-plugin.md) points at
it:

```kotlin
kotrail {
    configFile = layout.projectDirectory.file("kotrail.properties")
}
```

The file:

```properties
# kotrail.properties
compose.maxNesting=4
narrowModelParameters.scope=all
rules.noPassThroughReturn=false
```

A build that wires the compiler plugin by hand passes the same keys as plugin options:

```kotlin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-P", "plugin:com.kitakkun.kotrail:configFile=${projectDir.resolve("kotrail.properties")}",
        "-P", "plugin:com.kitakkun.kotrail:compose.maxNesting=6",   // overrides the file
    )
}
```

## Test source sets

The compiler sees one compilation at a time, so per-source-set settings are a Gradle concern. The
Gradle plugin has a block for it, matching every compilation whose name contains `test`, plus one
for a single compilation by name. The files are layered, so an override's file lists only what it
changes:

```kotlin
kotrail {
    configFile = layout.projectDirectory.file("kotrail.properties")
    test {
        configFile = layout.projectDirectory.file("kotrail-test.properties")
    }
    compilation("androidTest") {
        configFile = layout.projectDirectory.file("kotrail-androidtest.properties")
    }
}
```

```properties
# kotrail-test.properties: everything in kotrail.properties still applies
rules.preferExplicitBackingField=false
rules.compose.nesting=false
```

A build that wires the compiler plugin by hand gives `compileTestKotlin` its own file instead:

```properties
# kotrail-test.properties
rules.preferExplicitBackingField=false
rules.compose.nesting=false
```

```kotlin
tasks.withType<KotlinCompile>().configureEach {
    val file = if (name.contains("Test")) "kotrail-test.properties" else "kotrail.properties"
    compilerOptions.freeCompilerArgs.addAll(
        "-P", "plugin:com.kitakkun.kotrail:configFile=${projectDir.resolve(file)}",
    )
}
```

Turning `rules.compose.windowInsets` off in a *main* source set also stops the inferred insets
metadata from being written, so other modules can no longer verify calls into it. Turning it
off in tests has no such effect.

## Suppressing a single occurrence

Every Kotrail diagnostic can be suppressed by name, per declaration or per file, exactly like a
built-in one:

```kotlin
@Suppress("COMPOSABLE_NESTING_TOO_DEEP")
@Composable
fun LegacyScreen() { ... }
```

## In the compiler tests

Every rule is switched off before a fixture is compiled, and the fixture opts in to the ones it
exercises through a directive on its first lines. The values go through the same command-line
processor as real builds:

```kotlin
// KOTRAIL_CONFIG: rules.compose.nesting=true, compose.maxNesting=2
```

Opting in keeps each fixture about one rule, and means a newly added rule cannot start reporting
across fixtures that were written for something else. It is the opposite of the default a real
build gets, where every rule is on.

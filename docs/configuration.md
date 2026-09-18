# Configuration

Every rule is on, at error severity, with no configuration at all. Settings live in a
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
      blockingInCompose: fqn(kotlinx.coroutines.runBlocking) && composable

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
| `note` | Text appended to every Kotrail message. See [Project notes](#project-notes). |
| `exclude` | A predicate over locations; matching diagnostics of every rule are dropped. See [Excluding by pattern](#excluding-by-pattern). |
| `test.annotations` | Fully qualified annotations that mark a function as a test; shared by the test rules and the `test` predicate. Replaces the default list. |
| `rules.<rule>` | One entry per rule, named by the rule's full key (`functionLength`, `compose.nesting`, `test.naming`), as a shorthand or a mapping. |

A rule entry is either a **shorthand**, for the common case,

```yaml
rules:
  preferValueClass: off        # or on
  commentLength: warning       # on, at that severity
  noNotNullAssertion: error
```

or a **mapping** of the four keys every rule has and the rule's own settings:

```yaml
rules:
  functionLength:
    enabled: true              # the switch
    severity: error            # error or warning
    note: See ADR-014.         # appended to this rule's messages; wins over the top-level note
    exclude: name(main)        # locations this rule skips
    maxLines: 60               # the rule's settings, listed on its page
    maxComposableLines: 100
```

Rule keys are never split on dots: `compose.nesting` is one key under `rules`, and a nested
`compose:` group is an error. Diagnostics without a switch of their own
(`compose.windowInsetsUnverifiable`) accept `error` and `warning` but not `on` or `off`.

## Rules and their settings

Every rule takes `enabled`, `severity`, `note`, and `exclude`. The settings below are the rule's
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
| `functionLength` | `maxLines` | `50` | Most lines of code a function body may have; `0` for unlimited. |
| | `maxComposableLines` | `80` | The same limit for `@Composable` functions. |
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
| `compose.composablesPerFile` | `max` | `3` | Maximum non-private UI composables in one file, previews excluded; `0` disables. |
| `test.naming` | `style` | `backticked` | `backticked` for a sentence name, `identifier` for targets that reject spaces (Android instrumented tests). |
| | `minWords` | `3` | Words a backticked test name must have; `2` requires backticks only, `1` accepts any name. |

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
   it with an explicit backing field instead. ViewModels expose StateFlow directly here; see ADR-014.
```

The note is **appended** to the built-in message, never substituted for it, so the rewrite a rule
asks for cannot be lost by configuring one. A rule's own `note` replaces the top-level `note` for
that rule; leading and trailing whitespace is trimmed and a separating space is added.

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
| `enabled`, `note`, `exclude`, `test.annotations` | as in the file; a list is comma separated |
| `rules.<rule>` | `off`, `on`, `error`, or `warning` |
| `rules.<rule>.enabled` / `.severity` / `.note` / `.exclude` | as in the file |
| `rules.<rule>.<setting>` | as in the file; a list is comma separated |
| `rules.<rule>.<map setting>` | `<name>=<value>`, one entry per occurrence; `<name>=` drops the entry |

An empty value unsets the key, like `~` in the file. The compiler splits a `-P` value on commas,
so a list that needs more than one item belongs in the file.

## Suppressing a single occurrence

Every Kotrail diagnostic can be suppressed by name, per declaration or per file, exactly like a
built-in one. The names all start with `KOTRAIL_`, so a suppression says where the rule comes
from and cannot meet a compiler diagnostic of the same name:

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

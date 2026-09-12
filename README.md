<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/public/kotrail-lockup-dark.svg">
    <img src="docs/public/kotrail-lockup.svg" alt="Kotrail" width="242" height="72">
  </picture>
</p>

**A flexible set of compiler checker rules that keep your Kotlin code durable when developing with AI.**

Kotrail is a Kotlin compiler plugin (K2 / FIR, with IR support where cross-module metadata is
needed) that adds checks the standard compiler does not provide. It turns the conventions your
team already agrees on into compile errors, so code written by AI assistants stays on the rails
instead of drifting a little further with every generation.

> **Status: early development.** Thirty-two rules ship today, applied through a Gradle plugin.
> Every rule can be switched off or demoted to a warning, per project and per compilation.
> Nothing is published yet. Feedback on the direction is very welcome.

## Why

AI coding assistants are fast, but they are not consistent. Given the same codebase they will
happily produce five different styles of the same thing: a `!!` here, a stray `println` there, a
new data class where a value class was the convention, a `GlobalScope` launch that nobody asked
for. Each individual choice is defensible. Together they erode the shape of the code.

Prompts and `CLAUDE.md`-style instruction files help, but they are advisory. The model can forget
them, misread them, or simply run out of context. Linters help too, but they run after the fact and
are easy to skip.

The compiler is the one gate every line of code has to pass. Kotrail puts your conventions there.

- **Enforced, not suggested.** A rule violation is a compile error. The assistant sees it in the
  same loop it sees type errors, and fixes it before you ever review the change.
- **Flexible.** Every rule has a switch and its thresholds are tunable, per project and per
  source set.
- **Precise.** Rules run on the resolved FIR tree, not on text or tokens, so they can reason about
  types, receivers, annotations, and call targets rather than pattern-match on source.
- **Honest.** A rule reports only when the rewrite it asks for is guaranteed to preserve behavior.

## Rules

Full pages, with every condition and fixture, live under [`docs/rules/`](docs/rules/README.md).

| Rule | Rejects | Asks for |
|---|---|---|
| [Prefer explicit backing fields](docs/rules/prefer-explicit-backing-field.md) | `private val _items` exposed through `val items` | `val items: StateFlow<...>` with `field = MutableStateFlow(...)` (Kotlin 2.4) |
| [Narrow model parameters](docs/rules/narrow-model-parameters.md) | A data-class parameter of which the function reads only a few properties | The values it reads, or a smaller model |
| [No pass-through return](docs/rules/no-pass-through-return.md) | A function that returns one of its inputs unchanged on every path | `Unit`, or a computed result |
| [No pass-through function](docs/rules/no-pass-through-function.md) | `fun persist(user: User) = store(user)`: another function under a new name, same call shape | Call the target directly, or make the wrapper do something |
| [Prefer function references](docs/rules/prefer-function-references.md) | `{ transform(it) }`, `{ it.name }`, `{ repo.save(it) }` | `::transform`, `User::name`, `repo::save` |
| [Comment length](docs/rules/comment-length.md) | More than 5 consecutive `//` lines or a block comment longer than 5 lines | Shorter comments; KDoc for documentation |
| [No FQN references](docs/rules/no-fqn-references.md) | `java.util.UUID.randomUUID()`, `val f: java.io.File` | `import java.util.UUID` and a simple name |
| [No redundant else](docs/rules/no-redundant-else.md) | `else ->` on a `when` that already covers every case | Remove it, so a new case fails to compile |
| [Prefer value class](docs/rules/prefer-value-class.md) | `data class UserId(val value: String)` | `@JvmInline value class UserId(val value: String)` |
| [Forbidden call](docs/rules/forbidden-call.md) | Calls to callables listed in `forbiddenCall.functions` | Whatever the project prescribes instead |
| [No not-null assertion](docs/rules/no-not-null-assertion.md) | `x!!` | `?.`, `?:`, `requireNotNull`, smart casts |
| [No swallowed cancellation](docs/rules/no-swallowed-cancellation.md) | `catch (e: Exception)` in a suspend context that does not rethrow | Rethrow `CancellationException` or catch a narrower type |
| [No ignored exception](docs/rules/no-ignored-exception.md) | A catch clause that never touches the caught exception | Handle it, rethrow it, or name it `_` deliberately |
| [Prefer expression body](docs/rules/prefer-expression-body.md) | `fun f() { return x }` | `fun f() = x` |
| [No mutable collection in public API](docs/rules/no-mutable-collection-in-public-api.md) | `fun items(): MutableList<Item>` | `List<Item>` |
| [No data class in public API](docs/rules/no-data-class-in-public-api.md) | `public data class Config(...)` in a module with explicit API mode | A regular class with explicit `equals`/`hashCode`, or `internal` |
| [Visibility policy](docs/rules/visibility-policy.md) | A declaration matching `visibilityPolicy.private=composable && name(*Preview)` that is not private | The visibility the policy names |
| [Named arguments for repeated types](docs/rules/named-arguments-for-repeated-types.md) | `Padding(8, 16, 8, 16)` | `Padding(start = 8, top = 16, end = 8, bottom = 16)` |
| [Must be serializable](docs/rules/must-be-serializable.md) | `rememberSerializable { Filter() }`, or `save<@MustBeSerializable T>(value)`, with a type that is not `@Serializable` | `@Serializable` on the class, or an explicit serializer |
| [No unimplemented code](docs/rules/no-unimplemented.md) | `TODO()`, `throw NotImplementedError()` (switch it off for debug and test compilations) | The implementation, or an explicit `UnsupportedOperationException` |
| [Preconditions](docs/rules/preconditions.md) | `retry(-1)` where `retry` starts with `require(times >= 0)`; arguments folded through constants and locals, contracts carried across modules as metadata | Arguments that satisfy the callee's own `require` / `check` |
| [Function length](docs/rules/function-length.md) | A function body over 50 lines of code (80 for a composable); blank, brace-only, and comment lines do not count | Extraction into named pieces |
| [Window insets handling](docs/rules/compose/window-insets.md) (Compose) | A `@HandlesWindowInsets` contract that the body does not satisfy; insets applied twice | Contracts verified across modules through inferred metadata |
| [State delegation](docs/rules/compose/state-delegation.md) (Compose) | `val count = remember { mutableStateOf(0) }` used only through `.value` | `var count by remember { ... }` |
| [Nesting limit](docs/rules/compose/nesting.md) (Compose) | Composable calls nested deeper than the limit | Extracting the subtree into its own composable |
| [No trailing callback](docs/rules/compose/no-trailing-callback.md) (Compose) | `onClick: () -> Unit` as the last parameter of a UI composable | Callbacks before the optional parameters; `content` last |
| [Composable naming](docs/rules/compose/naming.md) (Compose) | `fun userCard()` emitting UI, `fun RememberState()` returning a value | PascalCase for UI, camelCase for values |
| [Modifier parameter](docs/rules/compose/modifier-parameter.md) (Compose) | A Modifier parameter that is misnamed, has no `Modifier` default, is out of place, or is applied twice | `modifier: Modifier = Modifier`, first optional parameter, applied once |
| [Named callback arguments](docs/rules/compose/named-callback-arguments.md) (Compose) | `IconButton { ... }` passing a callback as a trailing lambda | `IconButton(onClick = { ... })` |
| [Preview required](docs/rules/compose/preview-required.md) (Compose) | A UI composable whose file has no `@Preview` calling it | A preview composable next to it |
| [Composables per file](docs/rules/compose/composables-per-file.md) (Compose) | More than 3 non-private UI composables in one file | One component (and its helpers) per file |
| [Test naming](docs/rules/test/naming.md) (Test) | `@Test fun returnsEmptyList()` | `` @Test fun `returns an empty list when nothing matches`() `` |

Ideas not started yet: `required-annotation` (predicate-driven), `forbidden-supertype`.

## Configuration

```kotlin
plugins {
    kotlin("jvm") version "2.4.0"
    id("com.kitakkun.kotrail") version "0.1.0"
}

kotrail {
    configFile = layout.projectDirectory.file("kotrail.properties")
    test {
        configFile = layout.projectDirectory.file("kotrail-test.properties")
    }
}
```

Every rule is on at error severity with no configuration at all. A structural carve-out is a
predicate in the properties file, such as `exclude=package(com.acme.generated.*)` or
`exclude.noPassThroughFunction=extension(kotlin.String)`. See
[`docs/gradle-plugin.md`](docs/gradle-plugin.md) for the whole DSL and the artifact scheme, and
[`docs/configuration.md`](docs/configuration.md) for every key, precedence, the properties-file
form, and suppression.

## Project layout

```
kotrail/
├── plugin/                        # the compiler plugin
│   └── src/main/kotlin/com/kitakkun/kotrail/
│       ├── fir/checkers/          # general rules
│       ├── fir/compose/checkers/  # Compose rules: nesting limit, state delegation
│       ├── fir/compose/insets/    # insets analysis service, expression evaluator, checkers
│       ├── compose/insets/        # insets algebra shared by FIR and IR
│       └── ir/compose/insets/     # writes @InferredWindowInsetsHandling into metadata
│   └── src/{k240,k2321}/kotlin/   # com.kitakkun.kotrail.compat, one directory per Kotlin family
├── gradle-plugin/                 # the `com.kitakkun.kotrail` Gradle plugin and its DSL
├── annotations/           # @HandlesWindowInsets, @MustBeSerializable (ship with your app)
├── sample/                        # plain JVM sample; violations/ holds rejected code
├── sample-compose/                # Compose Multiplatform desktop samples
│   ├── lib/                       # composables without contracts (metadata is inferred)
│   └── app/                       # screens with contracts verified across the module boundary
├── compiler-tests/                # FIR / IR tests on the official Kotlin compiler test framework
│   ├── testData/diagnostics/      # <!DIAGNOSTIC!> marker fixtures
│   ├── testData/box/              # multi-module box tests with IR golden dumps
│   ├── testData-k2321/            # fixtures that differ on Kotlin 2.3.x
│   └── compose-stubs/             # stand-ins for the Compose declarations the rules recognize
└── docs/                          # rule pages and configuration guide
```

## Building and testing

Requirements: Kotlin 2.3.21 or 2.4.0, JDK 21 and Gradle 9.5 (the wrapper is included).

```bash
./gradlew build                                  # builds everything and runs the compiler tests
./gradlew :compiler-tests:test                   # FIR diagnostic tests and IR box tests only
./gradlew :compiler-tests:test -PupdateTestData=true   # rewrite expected markers and golden files
./gradlew :sample:run
./gradlew :sample-compose:app:compileKotlin
./gradlew build -Pkotlin.compiler=2.3.21          # build against the other supported Kotlin
```

The Kotlin version the build uses is selected by the `kotlin.compiler` Gradle property and defaults
to 2.4.0. See [supported Kotlin versions](docs/supported-kotlin-versions.md) for the version table,
the per-version source-set layout and how to add a new Kotlin version.

To see a rule reject code, copy a file from `sample/violations/` or `sample-compose/app/violations/`
into the matching source set and compile. Diagnostic names are rendered in the output through
`-Xrender-internal-diagnostic-names`, which the samples enable.

New fixtures under `compiler-tests/testData/` become tests after
`./gradlew :compiler-tests:generateTests`, which `build` runs automatically.

Releases are cut from CI by pushing a `v*` tag; the compiler plugin is published once per
supported Kotlin version. See [`docs/publishing.md`](docs/publishing.md).

## Roadmap

- [x] Plugin skeleton, FIR checker infrastructure, official test infrastructure
- [x] Thirty-two rules, each switchable and severity-tunable, with settings from plugin options or a properties file
- [ ] Gradle plugin (`kotrail { }` DSL, per-source-set settings, IDE support)
- [ ] Configuration-driven rules (`forbidden-call`, `required-annotation`, ...)
- [ ] User-extensible knowledge base for library composables that handle insets

## Contributing

Ideas for rules are the most useful contribution right now. If there is a constraint you keep
re-explaining to your AI assistant, open an issue describing what it should reject and why.

## License

Copyright 2026 kitakkun.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for the full text, or
<https://www.apache.org/licenses/LICENSE-2.0>.

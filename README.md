# Kotrail

**A flexible set of compiler checker rules that keep your Kotlin code durable when developing with AI.**

Kotrail is a Kotlin compiler plugin (K2 / FIR, with IR support where cross-module metadata is
needed) that adds checks the standard compiler does not provide. It turns the conventions your
team already agrees on into compile errors, so code written by AI assistants stays on the rails
instead of drifting a little further with every generation.

> **Status: early development.** Twenty-six rules ship today. Every rule can be switched off or
> demoted to a warning, from plugin options or a properties file; the Gradle plugin is still
> being designed. Feedback on the direction is very welcome.

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
| [Named arguments for repeated types](docs/rules/named-arguments-for-repeated-types.md) | `Padding(8, 16, 8, 16)` | `Padding(start = 8, top = 16, end = 8, bottom = 16)` |
| [Must be serializable](docs/rules/must-be-serializable.md) | `rememberSerializable { Filter() }`, or `save<@MustBeSerializable T>(value)`, with a type that is not `@Serializable` | `@Serializable` on the class, or an explicit serializer |
| [No unimplemented code](docs/rules/no-unimplemented.md) | `TODO()`, `throw NotImplementedError()` (switch it off for debug and test compilations) | The implementation, or an explicit `UnsupportedOperationException` |
| [Window insets handling](docs/rules/compose/window-insets.md) (Compose) | A `@HandlesWindowInsets` contract that the body does not satisfy; insets applied twice | Contracts verified across modules through inferred metadata |
| [State delegation](docs/rules/compose/state-delegation.md) (Compose) | `val count = remember { mutableStateOf(0) }` used only through `.value` | `var count by remember { ... }` |
| [Nesting limit](docs/rules/compose/nesting.md) (Compose) | Composable calls nested deeper than the limit | Extracting the subtree into its own composable |
| [No trailing callback](docs/rules/compose/no-trailing-callback.md) (Compose) | `onClick: () -> Unit` as the last parameter of a UI composable | Callbacks before the optional parameters; `content` last |
| [Composable naming](docs/rules/compose/naming.md) (Compose) | `fun userCard()` emitting UI, `fun RememberState()` returning a value | PascalCase for UI, camelCase for values |
| [Modifier parameter](docs/rules/compose/modifier-parameter.md) (Compose) | A Modifier parameter that is misnamed, has no `Modifier` default, is out of place, or is applied twice | `modifier: Modifier = Modifier`, first optional parameter, applied once |
| [Named callback arguments](docs/rules/compose/named-callback-arguments.md) (Compose) | `IconButton { ... }` passing a callback as a trailing lambda | `IconButton(onClick = { ... })` |
| [Preview required](docs/rules/compose/preview-required.md) (Compose) | A UI composable whose file has no `@Preview` calling it | A preview composable next to it |
| [Composables per file](docs/rules/compose/composables-per-file.md) (Compose) | More than 3 non-private UI composables in one file | One component (and its helpers) per file |

Ideas not started yet: `required-annotation` (predicate-driven), `forbidden-supertype`.

## Configuration

See [`docs/configuration.md`](docs/configuration.md) for every key, precedence, per-source-set
setup, and suppression. The short version:

```properties
# kotrail.properties
compose.maxNesting=4
narrowModelParameters.maxUnusedProperties=2
rules.noPassThroughReturn=false
```

```kotlin
// build.gradle.kts, until the Gradle plugin lands
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-P", "plugin:com.kitakkun.kotrail:configFile=${projectDir.resolve("kotrail.properties")}",
    )
}
```

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
├── annotations/           # @HandlesWindowInsets, @MustBeSerializable (ship with your app)
├── sample/                        # plain JVM sample; violations/ holds rejected code
├── sample-compose/                # Compose Multiplatform desktop samples
│   ├── lib/                       # composables without contracts (metadata is inferred)
│   └── app/                       # screens with contracts verified across the module boundary
├── compiler-tests/                # FIR / IR tests on the official Kotlin compiler test framework
│   ├── testData/diagnostics/      # <!DIAGNOSTIC!> marker fixtures
│   ├── testData/box/              # multi-module box tests with IR golden dumps
│   └── compose-stubs/             # stand-ins for the Compose declarations the rules recognize
└── docs/                          # rule pages and configuration guide
```

## Building and testing

Requirements: JDK 21 and Gradle 9.5 (the wrapper is included).

```bash
./gradlew build                                  # builds everything and runs the compiler tests
./gradlew :compiler-tests:test                   # FIR diagnostic tests and IR box tests only
./gradlew :compiler-tests:test -PupdateTestData=true   # rewrite expected markers and golden files
./gradlew :sample:run
./gradlew :sample-compose:app:compileKotlin
```

To see a rule reject code, copy a file from `sample/violations/` or `sample-compose/app/violations/`
into the matching source set and compile. Diagnostic names are rendered in the output through
`-Xrender-internal-diagnostic-names`, which the samples enable.

New fixtures under `compiler-tests/testData/` become tests after
`./gradlew :compiler-tests:generateTests`, which `build` runs automatically.

## Roadmap

- [x] Plugin skeleton, FIR checker infrastructure, official test infrastructure
- [x] Twenty-six rules, each switchable and severity-tunable, with settings from plugin options or a properties file
- [ ] Gradle plugin (`kotrail { }` DSL, per-source-set settings, IDE support)
- [ ] Configuration-driven rules (`forbidden-call`, `required-annotation`, ...)
- [ ] User-extensible knowledge base for library composables that handle insets

## Contributing

Ideas for rules are the most useful contribution right now. If there is a constraint you keep
re-explaining to your AI assistant, open an issue describing what it should reject and why.

## License

TBD

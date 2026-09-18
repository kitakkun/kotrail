# Contributing

Ideas for rules are the most useful contribution. If there is a constraint you keep
re-explaining to your AI assistant, open an issue describing what it should reject and why.
Pull requests for rules come with a fixture under `compiler-tests/testData/` and a page under
`docs/rules/`, so that the behavior is pinned and documented in the same change.

## Project layout

```
kotrail/
├── compiler-plugin/               # the compiler plugin
│   └── src/main/kotlin/com/kitakkun/kotrail/
│       ├── fir/checkers/          # general rules
│       ├── fir/compose/checkers/  # Compose rules: nesting limit, state delegation
│       ├── fir/compose/insets/    # insets analysis service, expression evaluator, checkers
│       ├── compose/insets/        # insets algebra shared by FIR and IR
│       └── ir/compose/insets/     # writes @InferredWindowInsetsHandling into metadata
│   └── src/{k2321,k240,k2420}/kotlin/  # com.kitakkun.kotrail.compat, one directory per Kotlin family
├── gradle-plugin/                 # the `com.kitakkun.kotrail` Gradle plugin and its DSL
├── annotations/           # @HandlesWindowInsets, @MustBeSerializable (ship with your app)
├── samples/
│   ├── jvm/                       # plain JVM sample; violations/ holds rejected code
│   └── compose/                   # Compose Multiplatform desktop samples
│       ├── lib/                   # composables without contracts (metadata is inferred)
│       └── app/                   # screens with contracts verified across the module boundary
├── compiler-tests/                # FIR / IR tests on the official Kotlin compiler test framework
│   ├── testData/diagnostics/      # <!DIAGNOSTIC!> marker fixtures
│   ├── testData/box/              # multi-module box tests with IR golden dumps
│   ├── testData-k2321/            # fixtures that differ on Kotlin 2.3.x
│   └── compose-stubs/             # stand-ins for the Compose declarations the rules recognize
└── docs/                          # rule pages and configuration guide
```

## Building and testing

Requirements: one of the supported Kotlin versions (2.3.21, 2.4.0, 2.4.10, 2.4.20), JDK 21 and Gradle 9.5 (the wrapper is included).

```bash
./gradlew build                                  # builds everything and runs the compiler tests
./gradlew :compiler-tests:test                   # FIR diagnostic tests and IR box tests only
./gradlew :compiler-tests:test -PupdateTestData=true   # rewrite expected markers and golden files
./gradlew :samples:jvm:run
./gradlew :samples:compose:app:compileKotlin
./gradlew build -Pkotlin.compiler=2.3.21          # build against another supported Kotlin
```

The Kotlin version the build uses is selected by the `kotlin.compiler` Gradle property and defaults
to the newest supported one. See [supported Kotlin versions](docs/supported-kotlin-versions.md) for the version table,
the per-version source-set layout and how to add a new Kotlin version.

To see a rule reject code, copy a file from `samples/jvm/violations/` or `samples/compose/app/violations/`
into the matching source set and compile. Diagnostic names are rendered in the output through
`-Xrender-internal-diagnostic-names`, which the samples enable.

New fixtures under `compiler-tests/testData/` become tests after
`./gradlew :compiler-tests:generateTests`, which `build` runs automatically.

Releases are cut from CI by pushing a `v*` tag; the compiler plugin is published once per
supported Kotlin version. See [`docs/publishing.md`](docs/publishing.md).

# The Gradle plugin

`com.kitakkun.kotrail` applies the compiler plugin to every Kotlin compilation of a project and
tells it which configuration files to read.

## Applying it

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    id("com.kitakkun.kotrail") version "0.1.0"
}
```

Apply a Kotlin plugin first: Kotrail reads the Kotlin version from it, and fails with an explicit
message if there is none.

That is the whole setup. Every rule not marked off by default is on, at error severity, and the annotations artifact is on
the compile classpath (as `compileOnly`, so it never reaches a published POM) so
`@HandlesWindowInsets` and `@MustBeSerializable` can be written.

## The `kotrail { }` block

```kotlin
kotrail {
    configFile = layout.projectDirectory.file("kotrail.yaml")

    test {
        configFile = layout.projectDirectory.file("kotrail-test.yaml")
    }

    compilation("androidTest") {
        configFile = layout.projectDirectory.file("kotrail-androidTest.yaml")
    }

    compilation("main") {
        // An IDE plugin that bundles these projects into its own class loader; see unloadable code.
        bundledConfigurations.add("hostRuntime")
    }

    // enabled = false           // do not run the plugin here at all
    // annotations = false       // do not add the annotations artifact
    // inferredMetadata = false  // write no inferred facts for other modules
    // consumerRules = false     // ship no shrinker rules to consumers
    // packagingExcludes = false // keep older libraries' facts files in an Android app's APK
}
```

| Property | Default | What it does |
| --- | --- | --- |
| `configFile` | none | The `kotrail.yaml` with rule settings; layered per compilation (below). |
| `enabled` | `true` | Whether the compiler plugin is applied at all. Per compilation. |
| `annotations` | `true` | Whether `kotrail-annotations` is added as `compileOnly` (Kotlin/JVM and Kotlin/Android). |
| `inferredMetadata` | `true` | Whether the inferred facts are written for the modules that compile against this one: the facts file `build/kotrail/facts/<target>-<compilation>/<project>.facts` on the JVM and Android (published as the `kotrail-facts` artifact), annotations (`@InferredStartsAsyncWork` and the rest) in a klib. `false` writes and publishes neither, for a library whose consumers do not use Kotrail; callers in other modules then see the module as if it had been compiled without Kotrail, while analysis within it is unchanged. Per compilation. See [Inferred metadata](inferred-metadata.md). |
| `consumerRules` | `true` | Whether the module ships `-dontwarn com.kitakkun.kotrail.**` to its consumers. The JVM and Android outputs of this version reference no Kotrail class (the facts are in a separate artifact), so the rule is only needed by consumers that also shrink JVM libraries built by earlier versions, whose class files carry the annotations. See [Consumer rules](#consumer-rules). |
| `packagingExcludes` | `true` | Whether an Android application (`com.android.application`) leaves `META-INF/kotrail/**` out of its APK and bundle, through `android.packaging.resources.excludes`. Only libraries built by earlier versions of Kotrail carry facts files there; this version publishes them as a separate artifact, so the exclude matters only for those older libraries. See [Publishing a library](inferred-metadata.md#publishing-a-library). |
| `bundledConfigurations` | empty | Configurations whose projects this compilation's artifact bundles, for unloadable code. Per compilation. |

Rule switches, severities, rule settings, and the project's own notes on why a rule exists are
**not** part of this DSL. They live in `kotrail.yaml`, whose keys are listed in
[configuration.md](configuration.md). One list of keys rather than two, and rule configuration
stays out of the build script.

## Per-compilation configuration

The compiler sees one compilation at a time, so relaxing a rule in tests is a Gradle concern.
`test { }` matches every compilation whose name contains `test` — `test`, `jvmTest`, `androidTest`,
`testDebugUnitTest` — and `compilation("name") { }` matches one by name.

**Files are layered, not replaced.** A compilation is given the project's file first, then the file
of every override that matches it, and a later file overrides only the entries it names. So an
override's file lists just the differences:

```yaml
# kotrail.yaml
rules:
  compose.nesting:
    maxDepth: 4
  commentLength: warning
```

```yaml
# kotrail-test.yaml — the nesting depth and the commentLength severity still apply
rules:
  preferExplicitBackingField: off
```

A compilation with `enabled = false` never gets the compiler plugin on its classpath at all,
which is different from `enabled: false` in a file: there the plugin loads and then returns.

Configuration files are registered as inputs of the compile task, so editing one recompiles.

## Artifacts

| Coordinate | What it is |
| --- | --- |
| `com.kitakkun.kotrail:kotrail-gradle-plugin` | This plugin. One build serves every supported Kotlin version. |
| `com.kitakkun.kotrail:kotrail-compiler-plugin` | The compiler plugin. Published once per supported Kotlin version. |
| `com.kitakkun.kotrail:kotrail-annotations` | `@HandlesWindowInsets`, `@MustBeSerializable`. |

The compiler plugin links against the Kotlin compiler's internal API, so a JAR only works on the
Kotlin version it was built against. Its version therefore leads with that Kotlin version:
`kotrail-compiler-plugin:2.4.0-0.1.0` is Kotrail 0.1.0 for Kotlin 2.4.0. The Gradle plugin reads
the Kotlin version the project applies and composes that coordinate, so a consumer never writes it
down.

A Kotlin version nothing was published for fails at configuration time with the versions that do
exist, rather than as a dependency-resolution error naming a coordinate the reader has never seen:

```
Kotrail 0.1.0 has no compiler plugin for Kotlin 2.2.20. It is published for 2.3.21, 2.4.0, 2.4.10, 2.4.20.
See docs/supported-kotlin-versions.md.
```

Setting `kotrail.compilerPluginVersion` overrides the whole artifact version and skips that check.
See [supported-kotlin-versions.md](supported-kotlin-versions.md).

## Applying fixes

Some rules know the exact rewrite that makes their finding go away: a lambda into a callable
reference, `{ return x }` into `= x`, `Cancel ->` into `is Cancel ->`, a redundant `else` gone,
names on positional arguments. Each rule page says so under **Fix**. Every compilation records
those fixes under `build/kotrail/fixes`, one file per source file together with the source's
content hash, and

```bash
./gradlew kotrailFix
```

applies them to the sources without compiling again. A rule whose edits the project would
rather make by hand keeps its diagnostic and records nothing with `fix: false` in kotrail.yaml
(per rule, or at the top level for all of them), so the task never touches what it was not
asked to. A file that changed since it was compiled
(edited by hand, or by a previous `kotrailFix`) is left alone until the next compilation refreshes
its record, so the loop is: compile, `kotrailFix`, compile again, until it reports nothing. Two
fixes that conflict in one file (one would insert inside text the other removes) are applied one
per round for the same reason, each fix whole or not at all. Run the formatter
afterwards: a deleted branch leaves its blank line.

The records are an output of the compile task, so they travel with the build cache and go with
`clean`. Nothing else reads them, but the format is plain JSON lines (the `fixesDir` option of the
compiler plugin), so an editor or an assistant can apply them too.

Next to the fixes, every compilation records the UI composables it declares under
`build/kotrail/composables`, and a compilation reads the records of the compilations it is
associated with (`main`, for `test` or for a `preview` compilation): that is how
[preview coverage](rules/compose/preview-coverage.md#a-preview-compilation-next-to-main) sees
`main` from a compilation that only has its class files. Likewise every compilation records
what [unloadable code](rules/unloadable-code.md) looks for under `build/kotrail/unloadable`, and
a compilation with that rule on reads the records of the projects on its runtime class path and
of the configurations named under `bundledConfigurations`.

## Multiplatform

Rules apply to every compilation of every target. The annotations artifact is multiplatform, but
it is added automatically (as `compileOnly`) only for Kotlin/JVM and Kotlin/Android, since the
Kotlin Gradle plugin does not support `compileOnly` on the other targets; a multiplatform build
that writes `@HandlesWindowInsets` or `@Unretained` adds `com.kitakkun.kotrail:kotrail-annotations`
to the source sets that need it, as `api` or `implementation`.

The facts the compiler plugin writes for callers in other modules never need the artifact. On
the JVM and Android they go to a facts file under `build/kotrail/facts`, outside the class
files, which name nothing of Kotrail; the JAR and the AAR carry nothing of it. In a klib they
are annotations (`@InferredStartsAsyncWork` and the rest), written against a stub class when
the artifact is absent, which a klib consumer without Kotrail drops silently. A published
library therefore carries the facts with no Kotrail dependency, `annotations = false` included
(every module still ships the `-dontwarn` [consumer rule](#consumer-rules), for the libraries of
earlier versions whose class files carry the annotations); one whose consumers do not use
Kotrail can leave them out with `inferredMetadata = false`. See
[Publishing a library](inferred-metadata.md#publishing-a-library).

## The `kotrail-facts` artifact

There is no DSL for the facts file; the plugin wires it for every JVM and Android compilation:

- The compilation writes `build/kotrail/facts/<target>-<compilation>/<project>.facts`, an output
  of its compile task.
- With `maven-publish`, the file of a main compilation is published next to the JAR or AAR as
  `<artifact>-<version>-kotrail-facts.facts`, a classifier artifact (classifier
  `kotrail-facts`, extension `facts`) of the Maven publication: for Kotlin/JVM on every Maven
  publication but the plugin markers of `java-gradle-plugin`; for each JVM target of a
  multiplatform project and the Android target of `com.android.kotlin.multiplatform.library`,
  through the target's publication; for each variant publication of `androidTarget()`, that
  variant's file; and for a Kotlin/Android library (`com.android.library`), on every Maven
  publication, the file of the variant its component
  (`android.publishing { singleVariant("release") }`) is named after, else of the variant the
  publication is named after, else of `release`. No software component publishes it as a
  variant, so the Gradle module metadata and the POM carry nothing of Kotrail. See
  [How it is published](inferred-metadata.md#how-it-is-published).
- A compilation reads the facts files of its dependencies: inside the build, a project
  dependency's through the consumable configuration `kotrailFactsElements`
  (`jvmKotrailFactsElements` for a multiplatform target), which no publication includes; a
  published library's by asking the repositories for the `kotrail-facts` classifier, leniently.
- The facts files are inputs of the compile task, and the compiler gets a digest of their
  contents (`factsHash`), so a changed fact in a library compiles the module that depends on it
  again even when the library's ABI stayed the same.

`--info` prints the facts files each compilation read (`Kotrail: facts files read: ...`). See
[Inferred metadata](inferred-metadata.md#the-facts-file-jvm-and-android) for the format and
the details.

## Consumer rules

With `consumerRules` on (the default), the task `kotrailConsumerRules` writes
`-dontwarn com.kitakkun.kotrail.**` to `build/kotrail/consumer-rules/META-INF/proguard/kotrail.pro`,
and the plugin ships it to the module's consumers in three layouts:

| Layout | How the rule ships |
|---|---|
| `org.jetbrains.kotlin.jvm`, and every `jvm` target of `org.jetbrains.kotlin.multiplatform` | As a resource of the main compilation, so the JAR carries `META-INF/proguard/kotrail.pro`, which R8 reads from every dependency JAR. |
| `com.android.library` | In `defaultConfig.consumerProguardFiles`, which the AAR ships as its `proguard.txt`. |
| `com.android.kotlin.multiplatform.library` | In `optimization.consumerKeepRules` of the `android { }` DSL (with `publish = true`), which the AAR ships as its `proguard.txt`. |

The Android routes are set in `androidComponents.finalizeDsl`, after the build script has run. The
`androidJvm` target of a multiplatform module does not get the resource: resources end up in the
AAR's `classes.jar`, where they are not consumer rules. A build wired another way (a hand-wired
compiler plugin, see below) adds the rule to its consumer rules itself.

## Without the Gradle plugin

The compiler plugin is a normal compiler plugin, so a build can wire it by hand instead. This is
what the samples in this repository do, since they build the plugin they use:

```kotlin
dependencies {
    kotlinCompilerPluginClasspath(project(":compiler-plugin"))
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-P", "plugin:com.kitakkun.kotrail:configFile=${projectDir.resolve("kotrail.yaml")}",
    )
}
```

`configFile` may be given more than once; the files are read in that order.

A JVM compilation writes its facts file only where `factsOut` says
(`-P plugin:com.kitakkun.kotrail:factsOut=<file>`), and reads the facts files named by `facts`
(`-P plugin:com.kitakkun.kotrail:facts=<file1>,<file2>`, or the option repeated). A hand-wired
build passes both itself and makes the files inputs of the dependent compile task, as
`samples/compose/lib` and `samples/compose/app` do. `inferredMetadata=false`
(`-P plugin:com.kitakkun.kotrail:inferredMetadata=false`) keeps the inferred facts out: no facts
file on the JVM, no annotation in a klib. A hand-wired build ships no consumer rules; its JVM
output needs none, since the facts file keeps Kotrail out of the class files.

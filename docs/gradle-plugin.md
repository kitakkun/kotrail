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
    // inferredMetadata = false  // write no inferred-fact annotations for other modules
    // consumerRules = false     // ship no shrinker rules to consumers
}
```

| Property | Default | What it does |
| --- | --- | --- |
| `configFile` | none | The `kotrail.yaml` with rule settings; layered per compilation (below). |
| `enabled` | `true` | Whether the compiler plugin is applied at all. Per compilation. |
| `annotations` | `true` | Whether `kotrail-annotations` is added as `compileOnly` (Kotlin/JVM and Kotlin/Android). |
| `inferredMetadata` | `true` | Whether the inferred-fact annotations (`@InferredStartsAsyncWork` and the rest) are written into the output for the modules that compile against this one. `false` leaves the class files and klibs free of Kotrail references, for a published library whose consumers do not use Kotrail; callers in other modules then see the module as if it had been compiled without Kotrail, while analysis within it is unchanged. Per compilation. See [Publishing a library](inferred-metadata.md#publishing-a-library). |
| `consumerRules` | `true` | Whether the module ships `-dontwarn com.kitakkun.kotrail.**` to its consumers, so that R8 or ProGuard in an app without Kotrail does not fail on the missing annotation classes. See [Consumer rules](#consumer-rules). |
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

The metadata the compiler plugin writes for callers in other modules (`@InferredEffectCapture`,
`@InferredStartsAsyncWork` and the rest) never needs the artifact: without it the plugin writes
the annotation against a stub class, on every target. A consumer with Kotrail reads it by name.
A consumer without Kotrail does not have the class: the JVM skips annotations it cannot load and
a klib consumer's partial linkage removes them silently, but R8 and ProGuard report the missing
class, which is why every module ships the `-dontwarn` [consumer rule](#consumer-rules)
(`consumerRules`). A published library therefore carries the metadata with no Kotrail
dependency, `annotations = false` included; one whose consumers do not use Kotrail can leave it
out with `inferredMetadata = false`. See [Publishing a library](inferred-metadata.md#publishing-a-library).

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

`inferredMetadata=false` (`-P plugin:com.kitakkun.kotrail:inferredMetadata=false`) keeps the
inferred-fact annotations out of the output. A hand-wired build ships no consumer rules; a
library built that way with the metadata on adds `-dontwarn com.kitakkun.kotrail.**` to its
consumer rules itself.

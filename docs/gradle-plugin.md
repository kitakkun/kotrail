# The Gradle plugin

`com.kitakkun.kotrail` applies the compiler plugin to every Kotlin compilation of a project and
forwards the `kotrail { }` block to it.

## Applying it

```kotlin
plugins {
    kotlin("jvm") version "2.4.0"
    id("com.kitakkun.kotrail") version "0.1.0"
}
```

Apply a Kotlin plugin first: Kotrail reads the Kotlin version from it, and fails with an explicit
message if there is none.

That is the whole setup. Every rule is on, at error severity, and the annotations artifact is on
the compile classpath so `@HandlesWindowInsets` and `@MustBeSerializable` can be written.

## The `kotrail { }` block

```kotlin
kotrail {
    // A properties file, if you prefer keeping settings out of the build script. Anything below
    // takes precedence over it.
    configFile = layout.projectDirectory.file("kotrail.properties")

    // Rule switches. Keys are the ones in configuration.md, without the `rules.` prefix.
    disable("commentLength", "compose.composablesPerFile")

    // Severities. Rules are errors by default; this reports one as a warning instead.
    warning("preferFunctionReferences")
    severity("noPassThroughReturn", KotrailSeverity.WARNING)

    // Rule settings, keyed exactly as in the properties file.
    setting("compose.maxNesting", 4)
    setting("narrowModelParameters.scope", "all")
    setting("test.annotations", listOf("kotlin.test.Test", "com.acme.Scenario"))

    // Overrides for test compilations: every compilation whose name contains "test".
    test {
        disable("preferExplicitBackingField")
    }

    // Overrides for one compilation by name. Android instrumented tests run on a device, which
    // rejects method names with spaces.
    compilation("androidTest") {
        setting("test.naming.style", "identifier")
    }

    // Turns the plugin off entirely, for a compilation or for the project.
    // enabled = false
}
```

Settings written directly in the block apply everywhere; `test` and `compilation` overrides are
merged on top for the compilations they match, so a later entry wins. A compilation the block
disables never gets the compiler plugin on its classpath at all.

Rule and setting keys are passed to the compiler as written. A key that does not exist fails the
build with `Unknown option`, rather than being silently ignored — see
[configuration.md](configuration.md) for the full list.

`annotations = false` stops the plugin from adding the annotations artifact, for a project that
does not use those two rules. Multiplatform projects add it to the source sets that need it
themselves; the automatic wiring covers Kotlin/JVM and Kotlin/Android.

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
down. Setting the `kotrail.compilerPluginVersion` Gradle property overrides the whole version, for
a locally published build.

See [supported-kotlin-versions.md](supported-kotlin-versions.md) for the versions that are
published and how a new one is added.

## Without the Gradle plugin

The compiler plugin is a normal compiler plugin, so a build can wire it by hand instead. This is
what the samples in this repository do, since they build the plugin they use:

```kotlin
dependencies {
    kotlinCompilerPluginClasspath(project(":plugin"))
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-P", "plugin:com.kitakkun.kotrail:configFile=${projectDir.resolve("kotrail.properties")}",
    )
}
```

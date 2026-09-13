# Supported Kotlin versions

Kotrail is a compiler plugin, so it links against the Kotlin compiler's internal API. That API
changes between Kotlin releases, and a plugin JAR only works on the compiler it was built against.
One source tree therefore has to be buildable against every Kotlin version Kotrail claims to
support, and CI has to prove it.

## The `kotlin.compiler` property

A single Gradle property selects the Kotlin version for the whole build — the Kotlin Gradle plugin,
`kotlin-compiler-embeddable` that the plugin compiles against, the compiler test framework
artifacts, the samples, and the compat sources described below.

```bash
./gradlew build                          # the default, currently Kotlin 2.4.0
./gradlew build -Pkotlin.compiler=2.3.21 # the same build against Kotlin 2.3.21
```

The default lives in `gradle.properties` (`kotlin.compiler=2.4.0`) so that `settings.gradle.kts`
and the project build scripts always read the same value. Passing an unsupported version fails
configuration with an explicit message rather than a confusing compile error.

## Supported versions

| Kotlin | Compat family | Notes |
| --- | --- | --- |
| 2.4.20 | `k2420` | `FirNamedFunctionChecker` and the `namedFunctionCheckers` bucket, `FirResolvedQualifier.classId` as an extension, `PluginGenerated` as a sealed class, `AbstractJvmBlackBoxCodegenTestBase` in the test framework, IR dumps named `.ir.txt`. |
| 2.4.10 | `k240` | Same API surface as 2.4.0. |
| 2.4.0 (default) | `k240` | Reference version. Explicit backing fields and context parameters are on by default. |
| 2.3.21 | `k2321` | Also covers 2.3.20. Explicit backing fields need `-Xexplicit-backing-fields`; the plugin module compiles with `-Xcontext-parameters`. |

Every version is built and tested on every push by `.github/workflows/ci.yml`.

## What gets published per version

The compiler plugin JAR only works on the Kotlin version it was built against, so it is published
once per supported version, with that version leading the artifact version:
`com.kitakkun.kotrail:kotrail-compiler-plugin:2.4.0-0.1.0`. The Gradle plugin and the annotations
are Kotlin-version independent and are published once, at the plain Kotrail version. The Gradle
plugin reads the Kotlin version a consumer applies and composes the compiler-plugin coordinate,
so consumers never write it down. See [gradle-plugin.md](gradle-plugin.md).

Releasing therefore means running the publish tasks once per supported Kotlin version
(`-Pkotlin.compiler=<version>`), and the Gradle plugin's own publication only once.

The list of published versions lives in the root `build.gradle.kts` as `publishedKotlinVersions`,
is baked into the Gradle plugin, and is what it checks a consumer's Kotlin version against. Adding
a version means adding it there, to the CI matrix, and to the table above.

## Source-set layout

Shared code lives in `plugin/src/main/kotlin` and must only use compiler APIs that every supported
version agrees on. Where the versions differ, the shared code calls into the package
`com.kitakkun.kotrail.compat`, which is implemented once per family:

```
plugin/src/
├── main/kotlin/          # shared; never references a version-specific compiler API directly
├── k2420/kotlin/         # com.kitakkun.kotrail.compat for Kotlin 2.4.20+
├── k240/kotlin/          # com.kitakkun.kotrail.compat for Kotlin 2.4.0 <= v < 2.4.20
└── k2321/kotlin/         # com.kitakkun.kotrail.compat for Kotlin 2.3.20 <= v < 2.4.0
```

Exactly one family directory is added to the `main` compilation, chosen by
`kotlinCompatFamily(...)` in the root `build.gradle.kts` — the one place that maps a version to a
family. Every family directory declares the *same* set of public declarations, so the shared code
compiles unchanged whichever one is active. Each compat declaration carries a KDoc line starting
with `Since <version>:` describing what changed upstream, so a shim can be retired once the
versions that needed it are dropped.

The compiler test framework renames its runner base classes too, so `compiler-tests` has the
same split: `test-fixtures/` is shared and `test-fixtures-<family>/` holds
`com.kitakkun.kotrail.test.compat`, currently one alias for the box-test base class.

## Test data overlays

Most fixtures under `compiler-tests/testData/` are shared. A few depend on the surrounding compiler
rather than on Kotrail — a standard diagnostic that only one version reports, or an IR dump whose
rendering changed — and those are overridden per family:

```
compiler-tests/
├── testData/            # shared fixtures; the only directory test discovery walks
├── testData-k2321/      # files that replace the same relative path when building on 2.3.x
└── testData-k2420/      # the same for 2.4.20+, where the framework names IR dumps `.ir.txt`
```

The override is resolved at run time by `TestDataOverlay` in `compiler-tests/test-fixtures`. It is
a whole-test override: put the `.kt` file *and* its golden files (`.fir.ir.txt`, …) in the overlay,
because the test framework derives golden file paths from the source path it is handed. An overlay
never adds or removes a test — discovery still walks `testData/` only.

`-PupdateTestData=true` rewrites whichever file the active version actually read, so updating
expectations on 2.3.21 touches the overlay and leaves the shared fixture alone.

Prefer sharing. Only add an overlay file when the difference is genuinely in the compiler.

## Adding a new Kotlin version

1. Add a family arm to `kotlinCompatFamily(...)` in the root `build.gradle.kts`, or extend an
   existing arm's range if the new version needs no new shims.
2. If it needs a new family, create `plugin/src/<family>/kotlin/com/kitakkun/kotrail/compat/` and
   copy every file from the closest existing family, then adjust it for that compiler's API.
   Keep the declaration names and signatures identical across families.
3. Build with `-Pkotlin.compiler=<version>` and fix the drift. When shared code fails to compile,
   move the offending call behind a new compat declaration rather than branching in `main`; add the
   same declaration to *all* family directories.
4. Run `./gradlew :compiler-tests:test -Pkotlin.compiler=<version>`. For a fixture that fails only
   because of compiler behavior, re-run with `-PupdateTestData=true`, review the diff, and move the
   rewritten files into `compiler-tests/testData-<family>/` — never leave a version-specific
   expectation in the shared `testData/`.
5. Add the version to `publishedKotlinVersions` in the root `build.gradle.kts`, to the matrix in
   `.github/workflows/ci.yml`, and to the table above.

## Notes

- `gradle.properties` sets `kotlin.compiler.execution.strategy=in-process`. Flipping
  `kotlin.compiler` between builds otherwise leaves one Kotlin daemon per version behind and Gradle
  warns about multiple daemon sessions.
- `gradle.properties` also pins `org.gradle.java.home` to a local JDK 21. CI overrides it with
  `-Dorg.gradle.java.home="$JAVA_HOME"`.

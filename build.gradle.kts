plugins {
    kotlin("jvm") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("org.jetbrains.compose") apply false
}

// The Kotlin compiler version this build compiles and tests against. Selected by the
// `kotlin.compiler` Gradle property, whose default lives in gradle.properties; the Kotlin Gradle
// plugin itself is resolved from the same property in settings.gradle.kts.
val kotlinCompilerVersion: String = providers.gradleProperty("kotlin.compiler").get()

/**
 * Maps a Kotlin compiler version to the compat "family" whose sources are compiled into the plugin
 * (`plugin/src/<family>/kotlin`). Every family directory declares the same set of
 * `com.kitakkun.kotrail.compat` declarations, implemented against that version's compiler API.
 * The family key also selects the per-version test data overlay
 * (`compiler-tests/testData-<family>`).
 *
 * Later phases add `k230` (2.3.0 <= v < 2.3.20), `k2220`, `k220`, `k210` and `k200`.
 */
fun kotlinCompatFamily(version: String): String {
    val numbers = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val major = numbers.getOrElse(0) { 0 }
    val minor = numbers.getOrElse(1) { 0 }
    val patch = numbers.getOrElse(2) { 0 }
    return when {
        major > 2 || (major == 2 && minor >= 4) -> "k240"
        major == 2 && minor == 3 && patch >= 20 -> "k2321"
        else -> error(
            "Kotlin $version is not supported by Kotrail; supported versions are 2.3.20 and newer. " +
                "See docs/supported-kotlin-versions.md.",
        )
    }
}

// Read by the subprojects that need to know which compiler they are building against.
extra["kotlinCompilerVersion"] = kotlinCompilerVersion
extra["kotlinCompatFamily"] = kotlinCompatFamily(kotlinCompilerVersion)

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}

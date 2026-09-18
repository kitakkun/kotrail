pluginManagement {
    // The Kotlin versions Kotrail publishes a compiler plugin for, oldest first. This is the one
    // list every other place derives from: the build defaults to the newest entry, the Gradle
    // plugin refuses a consumer's Kotlin version that is not here, and the table in
    // docs/supported-kotlin-versions.md and the CI matrix are kept in step with it by hand. It is
    // declared here, in the block Gradle evaluates before anything else, because the Kotlin
    // Gradle plugin has to be pinned to the same version.
    val publishedKotlinVersions = listOf("2.3.21", "2.4.0", "2.4.10", "2.4.20")

    // The Kotlin compiler version this build runs against: the newest published version, or the
    // one given with -Pkotlin.compiler=<version>. See docs/supported-kotlin-versions.md.
    val kotlinCompilerVersion: String =
        providers.gradleProperty("kotlin.compiler").getOrElse(publishedKotlinVersions.last())

    gradle.extra["publishedKotlinVersions"] = publishedKotlinVersions
    gradle.extra["kotlinCompilerVersion"] = kotlinCompilerVersion

    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

// Every version is in gradle/libs.versions.toml, except Kotlin's, which the catalog carries as a
// placeholder and this override replaces with the selected compiler version.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("kotlin", gradle.extra["kotlinCompilerVersion"] as String)
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kotrail"
include(
    "compiler-plugin",
    "gradle-plugin",
    "annotations",
    "samples:jvm",
    "samples:compose:lib",
    "samples:compose:app",
    "compiler-tests",
    "compiler-tests:compose-stubs",
)

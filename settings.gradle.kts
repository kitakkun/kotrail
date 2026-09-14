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

    // Compose Multiplatform is versioned independently of Kotlin, and 1.12.0 works with every
    // Kotlin version Kotrail supports today. Should a future supported version need a different
    // release, select it from kotlinCompilerVersion here.
    val composeVersion = "1.12.0"

    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        kotlin("jvm") version kotlinCompilerVersion
        id("org.jetbrains.kotlin.plugin.compose") version kotlinCompilerVersion
        id("org.jetbrains.compose") version composeVersion
        id("com.vanniktech.maven.publish") version "0.37.0"
        id("com.gradle.plugin-publish") version "2.2.1"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kotrail"
include(
    "plugin",
    "gradle-plugin",
    "annotations",
    "sample",
    "sample-compose:lib",
    "sample-compose:app",
    "compiler-tests",
    "compiler-tests:compose-stubs",
)

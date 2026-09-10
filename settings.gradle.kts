pluginManagement {
    // The Kotlin compiler version the whole build runs against. Override per invocation with
    // -Pkotlin.compiler=<version>; the default lives in gradle.properties so that this script and
    // the project scripts read the exact same value. See docs/supported-kotlin-versions.md.
    val kotlinCompilerVersion: String = providers.gradleProperty("kotlin.compiler").get()

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

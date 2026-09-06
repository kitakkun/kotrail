pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kotrail"
include(
    "plugin",
    "annotations",
    "sample",
    "sample-compose:lib",
    "sample-compose:app",
    "compiler-tests",
    "compiler-tests:compose-stubs",
)

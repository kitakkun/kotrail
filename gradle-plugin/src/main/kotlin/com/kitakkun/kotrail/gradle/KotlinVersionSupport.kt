package com.kitakkun.kotrail.gradle

import org.gradle.api.GradleException

/**
 * The compiler plugin links against the Kotlin compiler's internal API, so it is published for
 * one exact Kotlin version at a time. Asking for an artifact nobody published surfaces as a
 * dependency-resolution failure naming a coordinate the reader has never seen, so the Gradle
 * plugin checks the version first and says what is wrong.
 */
internal fun checkKotlinVersionIsSupported(
    kotlinVersion: String,
    kotrailVersion: String = KOTRAIL_VERSION,
    supported: List<String> = SUPPORTED_KOTLIN_VERSIONS,
) {
    if (kotlinVersion in supported) return
    throw GradleException(unsupportedKotlinVersionMessage(kotlinVersion, kotrailVersion, supported))
}

internal fun unsupportedKotlinVersionMessage(
    kotlinVersion: String,
    kotrailVersion: String,
    supported: List<String>,
): String = "Kotrail $kotrailVersion has no compiler plugin for Kotlin $kotlinVersion. " +
    "It is published for ${supported.joinToString()}. See docs/supported-kotlin-versions.md."

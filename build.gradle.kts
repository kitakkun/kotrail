import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.plugin.publish) apply false
}

// The Kotlin compiler version this build compiles and tests against. Selected by the
// `kotlin.compiler` Gradle property, whose default lives in gradle.properties; the Kotlin Gradle
// plugin itself is resolved from the same property in settings.gradle.kts.
// Chosen in settings.gradle.kts, where the Kotlin Gradle plugin is pinned to the same version.
val kotlinCompilerVersion: String = gradle.extra["kotlinCompilerVersion"] as String

/**
 * Maps a Kotlin compiler version to the compat "family" whose sources are compiled into the plugin
 * (`plugin/src/<family>/kotlin`). Every family directory declares the same set of
 * `com.kitakkun.kotrail.compat` declarations, implemented against that version's compiler API.
 * The family key also selects the per-version test data overlay
 * (`compiler-tests/testData-<family>`).
 *
 * Families: `k2420` (2.4.20 and newer), `k240` (2.4.0 <= v < 2.4.20), `k2321` (2.3.20 <= v < 2.4.0).
 */
fun kotlinCompatFamily(version: String): String {
    val numbers = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val major = numbers.getOrElse(0) { 0 }
    val minor = numbers.getOrElse(1) { 0 }
    val patch = numbers.getOrElse(2) { 0 }
    return when {
        major > 2 || (major == 2 && minor > 4) || (major == 2 && minor == 4 && patch >= 20) -> "k2420"
        major == 2 && minor == 4 -> "k240"
        major == 2 && minor == 3 && patch >= 20 -> "k2321"
        else -> error(
            "Kotlin $version is not supported by Kotrail; supported versions are 2.3.20 and newer. " +
                "See docs/supported-kotlin-versions.md.",
        )
    }
}

/**
 * The Kotlin versions a compiler plugin is published for, declared in `settings.gradle.kts`. The
 * artifact version pairs an exact Kotlin version with the Kotrail version, so this is a list of
 * exact versions rather than a range: the Gradle plugin refuses a consumer's Kotlin version that
 * is not here, instead of letting dependency resolution fail on a coordinate nobody published.
 */
@Suppress("UNCHECKED_CAST")
val publishedKotlinVersions = gradle.extra["publishedKotlinVersions"] as List<String>

// Read by the subprojects that need to know which compiler they are building against.
extra["kotlinCompilerVersion"] = kotlinCompilerVersion
extra["kotlinCompatFamily"] = kotlinCompatFamily(kotlinCompilerVersion)
extra["publishedKotlinVersions"] = publishedKotlinVersions

// What every published module shares: the Maven Central target, signing, and the POM fields that
// describe the project rather than one artifact. Each module adds its own coordinates, name and
// description. Uploads land in a Central Portal deployment that still has to be released;
// the release workflow runs `publishAndReleaseToMavenCentral`, which does both. Signing is only
// required for a non-snapshot version, so local builds and the functional test publish unsigned.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()
            pom {
                url.set("https://github.com/kitakkun/kotrail")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("kitakkun")
                        name.set("kitakkun")
                        url.set("https://github.com/kitakkun")
                    }
                }
                scm {
                    url.set("https://github.com/kitakkun/kotrail")
                    connection.set("scm:git:git://github.com/kitakkun/kotrail.git")
                    developerConnection.set("scm:git:ssh://git@github.com/kitakkun/kotrail.git")
                }
            }
        }
    }
}

allprojects {
    group = "com.kitakkun.kotrail"
    // The Kotrail version, shared by the annotations, the compiler plugin and the Gradle plugin.
    // The compiler plugin's published artifact prefixes it with the Kotlin version it was built
    // against; see plugin/build.gradle.kts.
    version = providers.gradleProperty("kotrail.version").getOrElse("0.1.0-SNAPSHOT")

    repositories {
        mavenCentral()
        google()
    }
}

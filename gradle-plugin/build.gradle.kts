// The Gradle plugin consumers apply as `id("com.kitakkun.kotrail")`. It is independent of the
// Kotlin version: it compiles against the oldest Kotlin Gradle plugin API Kotrail supports and
// resolves the compiler-plugin artifact matching the consumer's Kotlin version at configuration
// time.
import com.vanniktech.maven.publish.GradlePublishPlugin

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    alias(libs.plugins.plugin.publish)
    alias(libs.plugins.maven.publish)
}

val kotlinCompilerVersion: String by rootProject.extra

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The oldest Kotlin Gradle plugin API Kotrail supports. Newer Kotlin versions stay compatible
    // with it, so one Gradle plugin build serves every supported Kotlin.
    compileOnly(libs.kotlin.gradle.plugin.api)
    testImplementation(gradleTestKit())
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    website.set("https://github.com/kitakkun/kotrail")
    vcsUrl.set("https://github.com/kitakkun/kotrail")
    plugins {
        create("kotrail") {
            id = "com.kitakkun.kotrail"
            displayName = "Kotrail"
            description = "Compiler checker rules that keep Kotlin code durable when developing with AI"
            implementationClass = "com.kitakkun.kotrail.gradle.KotrailGradlePlugin"
            tags.set(listOf("kotlin", "compiler-plugin", "lint", "static-analysis", "compose"))
        }
    }
}

// The Kotrail version is baked in so that the plugin can name the artifacts it belongs with.
val generateVersionSource by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/kotrail-version/kotlin")
    val kotrailVersion = project.version.toString()
    @Suppress("UNCHECKED_CAST")
    val publishedKotlinVersions = rootProject.extra["publishedKotlinVersions"] as List<String>
    inputs.property("version", kotrailVersion)
    inputs.property("publishedKotlinVersions", publishedKotlinVersions)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("com/kitakkun/kotrail/gradle/KotrailPluginVersion.kt").asFile
        file.parentFile.mkdirs()
        val versions = publishedKotlinVersions.joinToString(", ") { "\"" + it + "\"" }
        file.writeText(
            "package com.kitakkun.kotrail.gradle\n\n" +
                "/** The Kotrail version this Gradle plugin was built from. */\n" +
                "internal const val KOTRAIL_VERSION: String = \"" + kotrailVersion + "\"\n\n" +
                "/** The Kotlin versions a compiler plugin is published for. */\n" +
                "internal val SUPPORTED_KOTLIN_VERSIONS: List<String> = listOf(" + versions + ")\n",
        )
    }
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateVersionSource)
}

// Maven Central carries the plugin and its marker alongside the other artifacts; the Gradle Plugin
// Portal (`publishPlugins`) is what lets `id("com.kitakkun.kotrail")` resolve with no repository
// setup. Shared POM metadata, Maven Central, and signing come from the root build script;
// com.gradle.plugin-publish already adds the sources and javadoc JARs, which is what
// GradlePublishPlugin accounts for.
mavenPublishing {
    coordinates(artifactId = "kotrail-gradle-plugin")
    configure(GradlePublishPlugin())
    pom {
        name.set("Kotrail Gradle plugin")
        description.set("Applies the Kotrail compiler plugin to a project's Kotlin compilations.")
    }
}

publishing {
    repositories {
        maven {
            name = "test"
            url = rootProject.layout.buildDirectory.dir("test-repo").get().asFile.toURI()
        }
    }
}

// The functional test drives a real consumer build, so every artifact it resolves has to be
// published to the local test repository first.
tasks.test {
    useJUnitPlatform()
    dependsOn(
        ":compiler-plugin:publishMavenPublicationToTestRepository",
        ":annotations:publishAllPublicationsToTestRepository",
        tasks.named("publishAllPublicationsToTestRepository"),
    )
    systemProperty("kotrail.test.repo", rootProject.layout.buildDirectory.dir("test-repo").get().asFile.absolutePath)
    systemProperty("kotrail.test.kotlinVersion", kotlinCompilerVersion)
    systemProperty("kotrail.test.version", project.version.toString())
}

// The Gradle plugin consumers apply as `id("com.kitakkun.kotrail")`. It is independent of the
// Kotlin version: it compiles against the oldest Kotlin Gradle plugin API Kotrail supports and
// resolves the compiler-plugin artifact matching the consumer's Kotlin version at configuration
// time.
plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
}

val kotlinCompilerVersion: String by rootProject.extra

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The oldest Kotlin Gradle plugin API Kotrail supports. Newer Kotlin versions stay compatible
    // with it, so one Gradle plugin build serves every supported Kotlin.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.3.21")
    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("kotrail") {
            id = "com.kitakkun.kotrail"
            displayName = "Kotrail"
            description = "Compiler checker rules that keep Kotlin code durable when developing with AI"
            implementationClass = "com.kitakkun.kotrail.gradle.KotrailGradlePlugin"
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

publishing {
    publications.withType<MavenPublication>().configureEach {
        // java-gradle-plugin creates `pluginMaven` (the plugin itself) and one marker publication.
        if (name == "pluginMaven") artifactId = "kotrail-gradle-plugin"
    }
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
        ":plugin:publishMavenPublicationToTestRepository",
        ":annotations:publishMavenPublicationToTestRepository",
        tasks.named("publishAllPublicationsToTestRepository"),
    )
    systemProperty("kotrail.test.repo", rootProject.layout.buildDirectory.dir("test-repo").get().asFile.absolutePath)
    systemProperty("kotrail.test.kotlinVersion", kotlinCompilerVersion)
    systemProperty("kotrail.test.version", project.version.toString())
}

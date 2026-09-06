import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("MainKt")
}

// Treat the plugin JAR as a build dependency, then pass its path to kotlinc via -Xplugin.
val compilerPlugin: Configuration by configurations.creating

dependencies {
    compilerPlugin(project(":plugin"))
}

tasks.withType<KotlinCompile>().configureEach {
    inputs.files(compilerPlugin)
    // Show diagnostic factory names ([PREFER_EXPLICIT_BACKING_FIELD]) in compiler output.
    compilerOptions.freeCompilerArgs.add("-Xrender-internal-diagnostic-names")
    compilerOptions.freeCompilerArgs.add(
        compilerPlugin.elements.map { files ->
            "-Xplugin=${files.first().asFile.absolutePath}"
        }
    )
}

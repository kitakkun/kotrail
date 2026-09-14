import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

val kotlinCompatFamily = rootProject.extra["kotlinCompatFamily"] as String

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
    // Show diagnostic factory names ([KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD]) in compiler output.
    compilerOptions.freeCompilerArgs.add("-Xrender-internal-diagnostic-names")
    // Counter.kt uses an explicit backing field. The feature is on by default from Kotlin 2.4.0;
    // 2.3.x still requires the opt-in flag.
    if (kotlinCompatFamily != "k240") {
        compilerOptions.freeCompilerArgs.add("-Xexplicit-backing-fields")
    }
    compilerOptions.freeCompilerArgs.add(
        compilerPlugin.elements.map { files ->
            "-Xplugin=${files.first().asFile.absolutePath}"
        }
    )
}

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Register Kotrail through the Kotlin Gradle plugin's own plugin classpath so that it
    // coexists with the Compose compiler plugin, which is registered the same way.
    kotlinCompilerPluginClasspath(project(":plugin"))
    implementation(project(":annotations"))
    implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.12.0")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xrender-internal-diagnostic-names")
}

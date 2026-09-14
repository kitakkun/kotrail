import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Register Kotrail through the Kotlin Gradle plugin's own plugin classpath so that it
    // coexists with the Compose compiler plugin, which is registered the same way.
    kotlinCompilerPluginClasspath(project(":plugin"))
    implementation(project(":annotations"))
    implementation(project(":sample-compose:lib"))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xrender-internal-diagnostic-names")
}

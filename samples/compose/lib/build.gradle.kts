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
    kotlinCompilerPluginClasspath(project(":compiler-plugin"))
    implementation(project(":annotations"))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xrender-internal-diagnostic-names")
}

// Without the Gradle plugin the facts file is wired by hand: the library writes what Kotrail
// infers about its composables to a file of its own, outside the class files, which
// :samples:compose:app reads. The directory is an output of the compile task, which the Kotlin
// plugin cleans before a full compilation and keeps for an incremental one; an incremental one
// reads the same file back (ownFacts) for the files it does not compile again.
tasks.named<KotlinCompile>("compileKotlin") {
    val factsDirectory = layout.buildDirectory.dir("kotrail/facts/jvm-main")
    outputs.dir(factsDirectory).withPropertyName("kotrailFactsDirectory")
    compilerOptions.freeCompilerArgs.addAll(
        factsDirectory.map {
            val file = it.file("lib.facts").asFile
            listOf("-P", "plugin:com.kitakkun.kotrail:factsOut=$file", "-P", "plugin:com.kitakkun.kotrail:ownFacts=$file")
        },
    )
}

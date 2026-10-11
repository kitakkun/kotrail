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

tasks.named<KotlinCompile>("compileKotlin") {
    val factsDirectory = layout.buildDirectory.dir("kotrail/facts/jvm-main")
    // The directory, not the file: before a non-incremental compilation the Kotlin plugin cleans
    // every output of the compile task and recreates it as a directory.
    outputs.dir(factsDirectory).withPropertyName("kotrailFactsDirectory")
    compilerOptions.freeCompilerArgs.addAll(
        factsDirectory.map {
            val file = it.file("lib.facts").asFile
            listOf("-P", "plugin:com.kitakkun.kotrail:factsOut=$file", "-P", "plugin:com.kitakkun.kotrail:ownFacts=$file")
        },
    )
}

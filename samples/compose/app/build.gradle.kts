import org.gradle.api.tasks.PathSensitivity
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
    implementation(project(":samples:compose:lib"))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xrender-internal-diagnostic-names")
}

tasks.named<KotlinCompile>("compileKotlin") {
    val libFacts = project(":samples:compose:lib").layout.buildDirectory.file("kotrail/facts/jvm-main/lib.facts")
    // An input of the compile task, so that a changed fact compiles the app again even when the
    // library's ABI stays the same.
    inputs.files(libFacts).withPropertyName("kotrailFacts").withPathSensitivity(PathSensitivity.NONE)
    compilerOptions.freeCompilerArgs.addAll(
        libFacts.map { listOf("-P", "plugin:com.kitakkun.kotrail:facts=${it.asFile}") },
    )
}

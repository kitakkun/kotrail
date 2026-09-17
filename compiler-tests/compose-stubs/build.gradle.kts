// Minimal stand-ins for the Compose declarations that the insets rule recognizes. The plugin
// only looks at fully qualified names and parameter types, so the compiler tests compile
// against these instead of pulling real Compose artifacts (and the Compose compiler) into
// the test classpath.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

// The Kotlin/Native stubs (kotlin.native.ref.WeakReference) live in the kotlin package, which
// only the standard library may normally declare.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xallow-kotlin-package")
}

dependencies {
    // The rememberSerializable stub takes a KSerializer, as the real API does.
    implementation(libs.kotlinx.serialization.core.jvm)
}

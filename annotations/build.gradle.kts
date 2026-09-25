// Annotations the rules recognize in user code. A multiplatform library, so that a contract such
// as `@Unretained` can be written in common code; consumers get it on their compile classpath,
// and the Gradle plugin adds it for Kotlin/JVM and Android projects by default.
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(21)
    jvm()
    js { browser(); nodejs() }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser(); nodejs() }
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()
}

// Shared POM metadata, Maven Central, and signing come from the root build script.
mavenPublishing {
    coordinates(artifactId = "kotrail-annotations")
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    pom {
        name.set("Kotrail annotations")
        description.set("Annotations recognized by the Kotrail compiler plugin: window insets contracts, composition local roots, serializability, lifetime contracts, and the metadata it writes.")
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

// The build compiles against several Kotlin versions, each wanting its own yarn lock for the JS
// tooling; the lock is not part of what this module publishes, so it is neither checked nor kept.
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    rootProject.extensions.configure<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension> {
        yarnLockMismatchReport = org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport.NONE
        reportNewYarnLock = false
        yarnLockAutoReplace = true
    }
}

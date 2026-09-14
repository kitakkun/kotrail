// Annotations the rules recognize in user code. An ordinary Kotlin/JVM library: consumers get it
// on their compile classpath, and the Gradle plugin adds it for them by default.
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(21)
}

// Shared POM metadata, Maven Central, and signing come from the root build script.
mavenPublishing {
    coordinates(artifactId = "kotrail-annotations")
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    pom {
        name.set("Kotrail annotations")
        description.set("Annotations recognized by the Kotrail compiler plugin: @HandlesWindowInsets, @MustBeSerializable.")
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

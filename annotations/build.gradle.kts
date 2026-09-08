// Annotations the rules recognize in user code. An ordinary Kotlin/JVM library: consumers get it
// on their compile classpath, and the Gradle plugin adds it for them by default.
plugins {
    kotlin("jvm")
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "kotrail-annotations"
        }
    }
    repositories {
        maven {
            name = "test"
            url = rootProject.layout.buildDirectory.dir("test-repo").get().asFile.toURI()
        }
    }
}

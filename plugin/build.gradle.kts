import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    `maven-publish`
}

val kotlinCompilerVersion: String by rootProject.extra
val kotlinCompatFamily: String by rootProject.extra

kotlin {
    jvmToolchain(21)
    // Version-specific shims live under plugin/src/<family>/kotlin. Exactly one family is
    // compiled into the JAR; shared code only ever references com.kitakkun.kotrail.compat.
    sourceSets.main {
        kotlin.srcDir("src/$kotlinCompatFamily/kotlin")
    }
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinCompilerVersion")
}

tasks.withType<KotlinCompile>().configureEach {
    // Checker `check()` overrides use context parameters. They are enabled by default from
    // Kotlin 2.4.0 on; 2.3.x still gates them behind the flag, and passing it on 2.4.0 would
    // only produce a "redundant flag" warning.
    if (kotlinCompatFamily != "k240") {
        compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")
    }
}

java {
    withSourcesJar()
}

// The JAR links against one Kotlin compiler, so the Kotlin version leads the artifact version:
// kotrail-compiler-plugin:2.4.0-0.1.0. The Gradle plugin composes the same coordinate from the
// Kotlin version a consumer applies. See docs/supported-kotlin-versions.md.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "kotrail-compiler-plugin"
            version = "$kotlinCompilerVersion-${project.version}"
            pom {
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "test"
            url = rootProject.layout.buildDirectory.dir("test-repo").get().asFile.toURI()
        }
    }
}

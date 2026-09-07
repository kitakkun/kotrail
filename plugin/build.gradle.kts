import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
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

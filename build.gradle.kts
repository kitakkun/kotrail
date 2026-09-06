plugins {
    kotlin("jvm") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.compose") version "1.12.0" apply false
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}

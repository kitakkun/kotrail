// FIR / IR level tests on JetBrains' compiler test framework. Test data lives in testData/,
// runners in test-fixtures/, and the JUnit classes are generated into test-gen/ (gitignored).
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
}

val kotlinCompatFamily = rootProject.extra["kotlinCompatFamily"] as String

dependencies {
    // The test framework links against the un-shaded compiler. The plugin bytecode has no
    // references to IntelliJ platform classes, so the JAR built against the embeddable
    // compiler loads fine here.
    testFixturesApi(project(":plugin"))
    testFixturesApi(libs.kotlin.test.junit5)
    testFixturesApi(libs.kotlin.compiler.internal.test.framework)
    testFixturesApi(libs.kotlin.compiler)
    testFixturesRuntimeOnly(libs.junit4)
}

sourceSets {
    test {
        java.setSrcDirs(listOf("test", "test-gen"))
        resources.setSrcDirs(listOf("testData"))
    }
    testFixtures {
        // The runner base classes the framework renames between Kotlin versions live under
        // test-fixtures-<family>/, one directory per family, like plugin/src/<family>.
        java.setSrcDirs(listOf("test-fixtures", "test-fixtures-$kotlinCompatFamily"))
    }
}

// JARs the framework locates through system properties.
val testArtifacts: Configuration by configurations.creating
// JARs that test data compiles against (annotations and Compose stand-ins).
val testDataClasspath: Configuration by configurations.creating

dependencies {
    testArtifacts(libs.kotlin.stdlib)
    testArtifacts(libs.kotlin.stdlib.jdk8)
    testArtifacts(libs.kotlin.reflect)
    testArtifacts(libs.kotlin.test)
    testArtifacts(libs.kotlin.script.runtime)
    testArtifacts(libs.kotlin.annotations.jvm)

    testDataClasspath(project(":annotations"))
    testDataClasspath(project(":compiler-tests:compose-stubs"))
    testDataClasspath(libs.kotlinx.coroutines.core.jvm)
    testDataClasspath(libs.kotlinx.serialization.core.jvm)
    // Test data for the test rules is compiled against the real JUnit annotations.
    testDataClasspath(libs.junit.jupiter.api)
}

val generateTests by tasks.registering(JavaExec::class) {
    inputs.dir(layout.projectDirectory.dir("testData"))
    outputs.dir(layout.projectDirectory.dir("test-gen"))
    classpath = sourceSets.testFixtures.get().runtimeClasspath
    mainClass.set("com.kitakkun.kotrail.test.GenerateTestsKt")
    workingDir = rootDir
}

tasks.compileTestKotlin { dependsOn(generateTests) }
tasks.matching { it.name == "compileTestJava" }.configureEach { dependsOn(generateTests) }

tasks.test {
    dependsOn(testArtifacts, testDataClasspath)
    useJUnitPlatform()
    workingDir = rootDir
    systemProperty("idea.home.path", rootDir)
    systemProperty("idea.ignore.disabled.plugins", "true")
    // ./gradlew :compiler-tests:test -PupdateTestData=true rewrites expected markers and golden files.
    systemProperty("kotlin.test.update.test.data", providers.gradleProperty("updateTestData").getOrElse("false"))
    // Fixtures that cannot be shared across Kotlin versions live in testData-<family>/ and take
    // precedence over the same relative path under testData/. See TestDataOverlay.
    val overlay = rootDir.resolve("compiler-tests/testData-$kotlinCompatFamily")
    if (overlay.isDirectory) {
        inputs.dir(overlay).withPropertyName("testDataOverlay")
    }
    systemProperty("kotrail.test.testDataOverlay", if (overlay.isDirectory) overlay.path else "")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib", "kotlin-stdlib")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib-jdk8", "kotlin-stdlib-jdk8")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-reflect", "kotlin-reflect")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-test", "kotlin-test")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-script-runtime", "kotlin-script-runtime")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-annotations-jvm", "kotlin-annotations-jvm")
    doFirst {
        systemProperty(
            "kotrail.test.classpath",
            testDataClasspath.files.joinToString(File.pathSeparator) { it.absolutePath },
        )
    }
}

fun Test.setLibraryProperty(propName: String, jarName: String) {
    val path = testArtifacts.files
        .find { """$jarName-\d.*""".toRegex().matches(it.name) }
        ?.absolutePath
        ?: error("testArtifacts is missing $jarName")
    systemProperty(propName, path)
}

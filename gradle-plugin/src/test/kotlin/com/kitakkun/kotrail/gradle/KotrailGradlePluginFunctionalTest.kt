package com.kitakkun.kotrail.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Drives a real consumer build against the artifacts published into the local test repository by
 * the `test` task, so the whole path is exercised: the plugin marker resolves, the Gradle plugin
 * asks for the compiler-plugin artifact whose version pairs the consumer's Kotlin version with
 * the Kotrail version, and the configuration files reach the compiler.
 */
class KotrailGradlePluginFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    private val repository: String = requireNotNull(System.getProperty("kotrail.test.repo"))
    private val kotlinVersion: String = requireNotNull(System.getProperty("kotrail.test.kotlinVersion"))
    private val kotrailVersion: String = requireNotNull(System.getProperty("kotrail.test.version"))

    @Test
    fun `a rule violation fails the consumer build`() {
        writeSettings()
        writeBuild(kotrailBlock = "")
        writeFile("src/main/kotlin/Cases.kt", BACKING_FIELD_VIOLATION)

        val result = runBuild("compileKotlin", expectFailure = true)

        assertTrue(result.output.contains("[Kotrail]"), result.output)
        assertTrue(result.output.contains("backing property"), result.output)
    }

    @Test
    fun `a rule switched off in the config file stops reporting`() {
        writeSettings()
        writeFile("kotrail.properties", "rules.preferExplicitBackingField=false")
        writeBuild(
            """
            kotrail {
                configFile = file("kotrail.properties")
            }
            """.trimIndent(),
        )
        writeFile("src/main/kotlin/Cases.kt", BACKING_FIELD_VIOLATION)

        val result = runBuild("compileKotlin")

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome, result.output)
        assertFalse(result.output.contains("[Kotrail]"), result.output)
    }

    @Test
    fun `a rule lowered to a warning reports without failing`() {
        writeSettings()
        writeFile("kotrail.properties", "severity.preferExplicitBackingField=warning")
        writeBuild(
            """
            kotrail {
                configFile = file("kotrail.properties")
            }
            """.trimIndent(),
        )
        writeFile("src/main/kotlin/Cases.kt", BACKING_FIELD_VIOLATION)

        val result = runBuild("compileKotlin")

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome, result.output)
        assertTrue(result.output.contains("[Kotrail]"), result.output)
    }

    @Test
    fun `a test compilation layers its own config file on top of the project one`() {
        writeSettings()
        writeFile("src/test/kotlin/CasesTest.kt", BACKING_FIELD_VIOLATION)
        writeFile("kotrail.properties", "severity.preferExplicitBackingField=warning")
        writeBuild(
            """
            kotrail {
                configFile = file("kotrail.properties")
                test {
                    configFile = file("kotrail-test.properties")
                }
            }
            """.trimIndent(),
        )

        // The override names an unrelated rule, so the project file's severity must still apply:
        // were the files replaced rather than layered, the rule would be an error again and the
        // build would fail.
        writeFile("kotrail-test.properties", "rules.commentLength=false")
        val layered = runBuild("compileTestKotlin")
        assertEquals(TaskOutcome.SUCCESS, layered.task(":compileTestKotlin")?.outcome, layered.output)
        assertTrue(layered.output.contains("[Kotrail]"), layered.output)

        // What the override does say wins over the project file.
        writeFile("kotrail-test.properties", "rules.preferExplicitBackingField=false")
        val overridden = runBuild("compileTestKotlin")
        assertEquals(TaskOutcome.SUCCESS, overridden.task(":compileTestKotlin")?.outcome, overridden.output)
        assertFalse(overridden.output.contains("[Kotrail]"), overridden.output)
    }

    private fun writeSettings() {
        writeFile(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    maven { url = uri("$repository") }
                    gradlePluginPortal()
                    mavenCentral()
                }
                plugins {
                    id("com.kitakkun.kotrail") version "$kotrailVersion"
                }
            }
            rootProject.name = "consumer"
            """.trimIndent(),
        )
    }

    private fun writeBuild(kotrailBlock: String) {
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "$kotlinVersion"
                id("com.kitakkun.kotrail")
            }

            repositories {
                maven { url = uri("$repository") }
                mavenCentral()
            }

            kotlin {
                jvmToolchain(21)
            }

            $kotrailBlock
            """.trimIndent(),
        )
    }

    private fun writeFile(path: String, content: String) {
        val file = File(projectDir, path)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun runBuild(vararg arguments: String, expectFailure: Boolean = false): BuildResult {
        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            // The artifacts under test are republished under the same snapshot version on every
            // build, so cached metadata would otherwise resolve a previous run's JAR.
            .withArguments(*arguments, "--refresh-dependencies")
            .forwardOutput()
        return if (expectFailure) runner.buildAndFail() else runner.build()
    }

    private companion object {
        val BACKING_FIELD_VIOLATION = """
            class Cases {
                private val _items = mutableListOf<String>()
                val items: List<String> get() = _items
            }
        """.trimIndent()
    }
}

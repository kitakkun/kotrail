package com.kitakkun.kotrail.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNull
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
        writeFile("src/main/kotlin/Cases.kt", NOT_NULL_ASSERTION_VIOLATION)

        val result = runBuild("compileKotlin", expectFailure = true)

        assertTrue(result.output.contains("[Kotrail]"), result.output)
        assertTrue(result.output.contains("!!"), result.output)
    }

    @Test
    fun `a rule switched off in the config file stops reporting`() {
        writeSettings()
        writeFile("kotrail.yaml", "rules:\n  noNotNullAssertion: off\n")
        writeBuild(
            """
            kotrail {
                configFile = file("kotrail.yaml")
            }
            """.trimIndent(),
        )
        writeFile("src/main/kotlin/Cases.kt", NOT_NULL_ASSERTION_VIOLATION)

        val result = runBuild("compileKotlin")

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome, result.output)
        assertFalse(result.output.contains("[Kotrail]"), result.output)
    }

    @Test
    fun `a rule lowered to a warning reports without failing`() {
        writeSettings()
        writeFile("kotrail.yaml", "rules:\n  noNotNullAssertion: warning\n")
        writeBuild(
            """
            kotrail {
                configFile = file("kotrail.yaml")
            }
            """.trimIndent(),
        )
        writeFile("src/main/kotlin/Cases.kt", NOT_NULL_ASSERTION_VIOLATION)

        val result = runBuild("compileKotlin")

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome, result.output)
        assertTrue(result.output.contains("[Kotrail]"), result.output)
    }

    @Test
    fun `the project's note is appended to the message`() {
        writeSettings()
        writeFile("kotrail.yaml", "rules:\n  noNotNullAssertion:\n    note: See ADR-014.\n")
        writeBuild(
            """
            kotrail {
                configFile = file("kotrail.yaml")
            }
            """.trimIndent(),
        )
        writeFile("src/main/kotlin/Cases.kt", NOT_NULL_ASSERTION_VIOLATION)

        val result = runBuild("compileKotlin", expectFailure = true)

        // The note is added to the built-in message, never substituted for it.
        assertTrue(result.output.contains("!!"), result.output)
        assertTrue(result.output.contains("See ADR-014."), result.output)
    }

    @Test
    fun `a test compilation layers its own config file on top of the project one`() {
        writeSettings()
        writeFile("src/test/kotlin/CasesTest.kt", NOT_NULL_ASSERTION_VIOLATION)
        writeFile("kotrail.yaml", "rules:\n  noNotNullAssertion: warning\n")
        writeBuild(
            """
            kotrail {
                configFile = file("kotrail.yaml")
                test {
                    configFile = file("kotrail-test.yaml")
                }
            }
            """.trimIndent(),
        )

        // The override names an unrelated rule, so the project file's severity must still apply:
        // were the files replaced rather than layered, the rule would be an error again and the
        // build would fail.
        writeFile("kotrail-test.yaml", "rules:\n  commentLength: off\n")
        val layered = runBuild("compileTestKotlin")
        assertEquals(TaskOutcome.SUCCESS, layered.task(":compileTestKotlin")?.outcome, layered.output)
        assertTrue(layered.output.contains("[Kotrail]"), layered.output)

        // What the override does say wins over the project file.
        writeFile("kotrail-test.yaml", "rules:\n  noNotNullAssertion: off\n")
        val overridden = runBuild("compileTestKotlin")
        assertEquals(TaskOutcome.SUCCESS, overridden.task(":compileTestKotlin")?.outcome, overridden.output)
        assertFalse(overridden.output.contains("[Kotrail]"), overridden.output)
    }

    @Test
    fun `kotrailFix applies the recorded fixes without compiling again`() {
        writeSettings()
        writeFile("kotrail.yaml", "severity: warning\n")
        writeBuild(
            """
            kotrail {
                configFile = file("kotrail.yaml")
            }
            """.trimIndent(),
        )
        writeFile("src/main/kotlin/Cases.kt", FIXABLE_CASES)

        val compiled = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, compiled.task(":compileKotlin")?.outcome, compiled.output)
        assertTrue(compiled.output.contains("KOTRAIL_PREFER_FUNCTION_REFERENCE"), compiled.output)

        val fixed = runBuild("kotrailFix")
        assertNull(fixed.task(":compileKotlin"), "kotrailFix must not compile: ${fixed.output}")
        assertEquals(TaskOutcome.SUCCESS, fixed.task(":kotrailFix")?.outcome, fixed.output)
        val text = File(projectDir, "src/main/kotlin/Cases.kt").readText()
        assertTrue(text.contains("names.map(::shout)"), text)
        assertTrue(text.contains("fun total(items: List<Int>): Int = items.sum()"), text)
        assertTrue(text.contains("is Action.Cancel -> println(\"cancel\")"), text)
        assertFalse(text.contains("else -> println(\"never\")"), text)
        assertTrue(text.contains("move(x = 1, y = 2, z = 3)"), text)

        val again = runBuild("kotrailFix")
        assertTrue(again.output.contains("changed since they were compiled"), again.output)
        assertEquals(text, File(projectDir, "src/main/kotlin/Cases.kt").readText())
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
        // A violation of a rule that behaves the same on every supported Kotlin version, so that
        // these tests are about the Gradle wiring rather than about a language feature.
        val NOT_NULL_ASSERTION_VIOLATION = """
            class Cases(private val name: String?) {
                fun length(): Int = name!!.length
            }
        """.trimIndent()

        // One case per rule that offers a fix; every fix is exercised by the kotrailFix test.
        val FIXABLE_CASES = """
            sealed interface Action {
                data class Save(val draft: Boolean) : Action
                data object Cancel : Action
            }

            fun shout(name: String): String = name.uppercase()

            fun move(x: Int, y: Int, z: Int) {}

            fun shoutAll(names: List<String>): List<String> = names.map { shout(it) }

            fun total(items: List<Int>): Int {
                return items.sum()
            }

            fun handle(action: Action) {
                when (action) {
                    is Action.Save -> println("save")
                    Action.Cancel -> println("cancel")
                    else -> println("never")
                }
                move(1, 2, 3)
            }
        """.trimIndent()
    }
}

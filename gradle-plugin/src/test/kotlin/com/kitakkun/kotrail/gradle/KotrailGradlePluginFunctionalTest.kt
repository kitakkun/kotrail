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
    fun `a Kotrail warning survives allWarningsAsErrors`() {
        writeSettings()
        writeFile("kotrail.yaml", "severity: warning\n")
        writeBuild(
            """
            kotrail {
                configFile = file("kotrail.yaml")
            }
            kotlin {
                compilerOptions {
                    allWarningsAsErrors = true
                }
            }
            """.trimIndent(),
        )
        writeFile("src/main/kotlin/Cases.kt", NOT_NULL_ASSERTION_VIOLATION)

        val result = runBuild("compileKotlin")

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome, result.output)
        assertTrue(result.output.contains("KOTRAIL_NOT_NULL_ASSERTION"), result.output)
        assertFalse(result.output.contains("-Werror"), result.output)
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
        // Two declarations moved into one branch keep their order, so the second may still read the first.
        assertTrue(text.contains("} else {\n        val base = count\n        val next = count + 1\n        println(base + next)"), text)

        val again = runBuild("kotrailFix")
        assertTrue(again.output.contains("changed since compiled"), again.output)
        assertEquals(text, File(projectDir, "src/main/kotlin/Cases.kt").readText())
    }

    @Test
    fun `a preview compilation checks the coverage of the main it is associated with`() {
        writeSettings()
        writeFile("kotrail.yaml", "severity: warning\nrules:\n  compose.previewRequired: off\n")
        writeFile("kotrail-preview.yaml", "rules:\n  compose.previewCoverage:\n    enabled: true\n    severity: error\n    packages: [com.acme.ui]\n")
        writeBuild(
            """
            kotlin {
                target.compilations.create("preview") {
                    associateWith(target.compilations.getByName("main"))
                }
            }

            kotrail {
                configFile = file("kotrail.yaml")
                compilation("preview") {
                    configFile = file("kotrail-preview.yaml")
                }
            }
            """.trimIndent(),
        )
        writeFile("src/main/kotlin/androidx/compose/runtime/Composable.kt", COMPOSABLE_STUB)
        writeFile("src/main/kotlin/androidx/compose/ui/tooling/preview/Preview.kt", PREVIEW_STUB)
        writeFile("src/main/kotlin/com/acme/ui/Cards.kt", UI_COMPOSABLES)
        writeFile(
            "src/preview/kotlin/com/acme/ui/Previews.kt",
            """
            package com.acme.ui

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            @Composable
            private fun CoveredPreview() {
                Covered("preview")
            }
            """.trimIndent(),
        )

        // main reaches the preview compilation as class files; what it declares comes from its record.
        val result = runBuild("compilePreviewKotlin", expectFailure = true)
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome, result.output)
        assertEquals(TaskOutcome.FAILED, result.task(":compilePreviewKotlin")?.outcome, result.output)
        assertTrue(result.output.contains("'com.acme.ui.Missing' has no preview in this compilation"), result.output)
        assertFalse(result.output.contains("'com.acme.ui.Covered' has no preview"), result.output)
        assertFalse(result.output.contains("KOTRAIL_PREVIEW_COVERAGE_PACKAGE_EMPTY"), result.output)
    }

    @Test
    fun `an unloadable module reports what the modules it bundles recorded`() {
        writeSettings(include = listOf("lib"))
        writeFile("kotrail.yaml", "severity: warning\nrules:\n  unloadableCode: on\n")
        writeBuild(
            """
            // The plugin bundles lib through a configuration of its own, not the runtime class path.
            val hostRuntime: Configuration by configurations.creating {
                isCanBeConsumed = false
                isCanBeResolved = true
            }

            dependencies {
                hostRuntime(project(":lib"))
            }

            kotrail {
                configFile = file("kotrail.yaml")
                compilation("main") {
                    bundledConfigurations.add("hostRuntime")
                }
            }
            """.trimIndent(),
        )
        writeFile(
            "lib/build.gradle.kts",
            """
            plugins {
                kotlin("multiplatform")
                id("com.kitakkun.kotrail")
            }

            repositories {
                maven { url = uri("$repository") }
                mavenCentral()
            }

            kotlin {
                jvmToolchain(21)
                // A multiplatform dependency: the JVM consumer must read the jvm records only, and the js
                // compilation's records must not become an input of the consumer.
                jvm {
                    val main = compilations.getByName("main")
                    compilations.create("preview") {
                        associateWith(main)
                    }
                }
                js { nodejs() }
            }
            """.trimIndent(),
        )
        writeFile("lib/src/jvmPreview/kotlin/Previews.kt", "object Previews")
        writeFile("lib/src/commonMain/kotlin/Shared.kt", "object Shared { val name = \"shared\" }")
        writeFile(
            "lib/src/jvmMain/kotlin/Buffers.kt",
            """
            object Buffers {
                val current: ThreadLocal<StringBuilder> = ThreadLocal()
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/kotlin/Plugin.kt",
            """
            class Plugin {
                fun start() {
                    Runtime.getRuntime().addShutdownHook(Thread())
                }
            }
            """.trimIndent(),
        )

        // Both the dependency's preview compilation and the consumer in one graph: Gradle validates that no task
        // reads another's output without depending on it.
        val result = runBuild("compileKotlin", ":lib:compilePreviewKotlinJvm", ":lib:compileKotlinJs")
        assertEquals(TaskOutcome.SUCCESS, result.task(":lib:compileKotlinJvm")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":lib:compilePreviewKotlinJvm")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":lib:compileKotlinJs")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome, result.output)
        // The rule is off in lib: its ThreadLocal is reported from the plugin module, with the file and line.
        assertFalse(result.output.contains("lib/src/main/kotlin/Buffers.kt:2:5"), result.output)
        assertTrue(result.output.contains("KOTRAIL_OUTBOUND_REFERENCE_IN_BUNDLED_CODE"), result.output)
        assertTrue(result.output.contains("a ThreadLocal, 'current'"), result.output)
        assertTrue(result.output.contains("Buffers.kt:2"), result.output)
        assertTrue(result.output.contains("KOTRAIL_UNSCOPED_REGISTRATION_IN_UNLOADABLE_CODE"), result.output)
    }

    private fun writeSettings(include: List<String> = emptyList()) {
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
            """.trimIndent() + include.joinToString("") { "\ninclude(\":$it\")" },
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

        // The annotations the Compose rules look for, by their real names, and a Text that draws: a
        // stub in this compilation is judged by its body, and invoking a composable slot counts.
        val COMPOSABLE_STUB = """
            package androidx.compose.runtime

            @Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
            annotation class Composable
        """.trimIndent()

        val PREVIEW_STUB = """
            package androidx.compose.ui.tooling.preview

            @Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
            annotation class Preview
        """.trimIndent()

        val UI_COMPOSABLES = """
            package com.acme.ui

            import androidx.compose.runtime.Composable

            @Composable
            fun Text(text: String, content: @Composable () -> Unit = {}) {
                content()
            }

            @Composable
            fun Covered(title: String) {
                Text(title)
            }

            @Composable
            fun Missing() {
                Text("missing")
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

            fun branch(count: Int, flag: Boolean) {
                val base = count
                val next = count + 1
                if (flag) {
                    println("flag")
                } else {
                    println(base + next)
                }
            }
        """.trimIndent()
    }
}

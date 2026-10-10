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
import java.util.zip.ZipFile

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
        writeFile("kotrail.yaml", "severity: warning\nrules:\n  narrativeOrder:\n    maxCallerDistance: 10\n    maxHelperNoise: 50\n")
        writeBuild(
            """
            kotrail {
                configFile = file("kotrail.yaml")
            }
            """.trimIndent(),
        )
        writeFile("src/main/kotlin/Cases.kt", FIXABLE_CASES)
        writeFile("src/main/kotlin/androidx/compose/runtime/Composable.kt", COMPOSABLE_STUB)
        writeFile("src/main/kotlin/androidx/compose/runtime/Remember.kt", REMEMBER_STUB)
        writeFile("src/main/kotlin/Keys.kt", REDUNDANT_KEY_CASES)
        writeFile("src/main/kotlin/Icons.kt", FAR_RUN_CASES)

        val compiled = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, compiled.task(":compileKotlin")?.outcome, compiled.output)
        assertTrue(compiled.output.contains("KOTRAIL_PREFER_FUNCTION_REFERENCE"), compiled.output)
        assertTrue(compiled.output.contains("KOTRAIL_REDUNDANT_KEY"), compiled.output)
        assertEquals(1, Regex("KOTRAIL_HELPER_FAR_FROM_CALLER").findAll(compiled.output).count(), compiled.output)

        val fixed = runBuild("kotrailFix")
        assertNull(fixed.task(":compileKotlin"), "kotrailFix must not compile: ${fixed.output}")
        assertEquals(TaskOutcome.SUCCESS, fixed.task(":kotrailFix")?.outcome, fixed.output)
        val text = File(projectDir, "src/main/kotlin/Cases.kt").readText()
        assertTrue(text.contains("names.map(::shout)"), text)
        // Every place a lambda can stand relative to its call takes the reference in the right spot.
        for (expected in listOf(
            "fun referenceInsideParentheses(list: List<Int>): List<Int> = list.map(::transform)",
            "fun referenceAfterAnotherArgument(list: List<Int>): Int = list.fold(0, ::combine)",
            "fun referenceAfterArguments(list: List<Int>): Int = list.fold(0, ::combine)",
            "run(::compute)",
            "users.map(User::name)",
            "users.forEach(repository::save)",
            "list.map(transform = ::transform)",
            "list.map(::transform).map(::transform)",
            "println(list.map(::transform))",
            "fun referenceMultiline(list: List<Int>): List<Int> = list.map(::transform)",
            "fun referenceLabeled(list: List<Int>): List<Int> = list.map(::transform)",
            "fun referenceLabeledInsideParentheses(list: List<Int>): List<Int> = list.map(::transform)",
            "val step: (Int) -> Int = ::transform",
            "run { ::compute }",
        )) {
            assertTrue(text.contains(expected), "expected `$expected` in:\n$text")
        }
        assertTrue(text.contains("fun total(items: List<Int>): Int = items.sum()"), text)
        assertTrue(text.contains("is Action.Cancel -> println(\"cancel\")"), text)
        assertFalse(text.contains("else -> println(\"never\")"), text)
        assertTrue(text.contains("move(x = 1, y = 2, z = 3)"), text)
        // Two declarations moved into one branch keep their order, so the second may still read the first.
        assertTrue(text.contains("} else {\n        val base = count\n        val next = count + 1\n        println(base + next)"), text)
        // A key that never changes goes with its comma; the only key becomes Unit.
        val keys = File(projectDir, "src/main/kotlin/Keys.kt").readText()
        assertTrue(keys.contains("remember(id, page) { id + page }"), keys)
        assertTrue(keys.contains("remember(Unit) { id }"), keys)
        // The far run moves right after its caller as one block, in its order.
        val icons = File(projectDir, "src/main/kotlin/Icons.kt").readText()
        assertTrue(
            icons.contains(
                "fun navigationBar(): String = homeIcon() + searchIcon() + inboxIcon() + profileIcon()\n\n" +
                    "private fun homeIcon(): String = \"home\"\n\nprivate fun searchIcon(): String = \"search\"\n\n" +
                    "private fun inboxIcon(): String = \"inbox\"\n\nprivate fun profileIcon(): String = \"profile\"\n\n" +
                    "fun beforeNavigationIcons(value: Int): String {",
            ),
            icons,
        )
        for (expected in listOf(
            "        val replaced = ready\n        ready = frame\n        counter++\n        return replaced\n",
            "        counter++\n        val previous = ready\n        ready = frame\n        return previous\n",
            "synchronized(lock) {\n        val previous = ready\n        ready = frame\n        previous\n    }",
            // In a braced branch, the statements go inside the branch's block.
            "} else {\n            val previous = ready\n            ready = frame\n            previous\n        }",
        )) {
            assertTrue(text.contains(expected), "expected `$expected` in:\n$text")
        }

        val again = runBuild("kotrailFix")
        assertTrue(again.output.contains("changed since compiled"), again.output)
        assertEquals(text, File(projectDir, "src/main/kotlin/Cases.kt").readText())

        // The fixed source still compiles, and none of the fixed findings comes back.
        val recompiled = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, recompiled.task(":compileKotlin")?.outcome, recompiled.output)
        assertFalse(recompiled.output.contains("KOTRAIL_PREFER_FUNCTION_REFERENCE"), recompiled.output)
        assertFalse(recompiled.output.contains("KOTRAIL_REDUNDANT_KEY"), recompiled.output)
        assertFalse(recompiled.output.contains("KOTRAIL_HELPER_FAR_FROM_CALLER"), recompiled.output)
    }

    @Test
    fun `kotrailFix applies only the fixes of the rules it is given`() {
        writeSettings()
        writeFile("kotrail.yaml", "severity: warning\n")
        writeBuild(
            """
            kotrail {
                configFile = file("kotrail.yaml")
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/kotlin/Tools.kt",
            """
            private fun resolveAdb(): String = "/usr/bin/adb"

            fun devices(): String = resolveAdb()

            fun total(items: List<Int>): Int {
                var sum = items.sum()
                return sum
            }
            """.trimIndent(),
        )

        val compiled = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, compiled.task(":compileKotlin")?.outcome, compiled.output)
        assertTrue(compiled.output.contains("KOTRAIL_HELPER_BEFORE_FIRST_USE"), compiled.output)
        assertTrue(compiled.output.contains("KOTRAIL_PREFER_VAL"), compiled.output)

        // A rule no record carries applies nothing and names the ones that do; matching is case-sensitive.
        val unknown = runBuild("kotrailFix", "--rules", "preferval")
        assertTrue(unknown.output.contains("Kotrail: nothing to fix for 'preferval'; recorded rules: narrativeOrder, preferVal"), unknown.output)

        // Only the move is applied; the var stays for another run.
        val moved = runBuild("kotrailFix", "--rules", "narrativeOrder")
        assertEquals(TaskOutcome.SUCCESS, moved.task(":kotrailFix")?.outcome, moved.output)
        val source = File(projectDir, "src/main/kotlin/Tools.kt")
        var text = source.readText()
        assertTrue(text.contains("fun devices(): String = resolveAdb()\n\nprivate fun resolveAdb(): String = \"/usr/bin/adb\""), text)
        assertTrue(text.contains("var sum = items.sum()"), text)
        assertTrue(moved.output.contains("Kotrail: src/main/kotlin/Tools.kt: 2 edits (narrativeOrder 2)"), moved.output)
        assertTrue(moved.output.contains("Kotrail: applied 2 edits in 1 file (narrativeOrder 2)"), moved.output)
        assertFalse(moved.output.contains("preferVal"), moved.output)

        // The Gradle property selects the rules too, once a compilation has refreshed the record.
        runBuild("compileKotlin")
        val narrowed = runBuild("kotrailFix", "-Pkotrail.fix.rules=preferVal")
        text = source.readText()
        assertTrue(text.contains("val sum = items.sum()"), text)
        assertTrue(narrowed.output.contains("Kotrail: applied 1 edit in 1 file (preferVal 1)"), narrowed.output)
    }

    @Test
    fun `a preview compilation checks the coverage of the main it is associated with`() {
        writeSettings()
        writeFile("kotrail.yaml", "severity: warning\nrules:\n  compose.previewRequired: off\n")
        writeFile("kotrail-preview.yaml", "rules:\n  compose.previewCoverage:\n    enabled: true\n    severity: error\n    packages: [com.acme.ui]\n    excludeNames: [com.acme.ui.Text]\n")
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

        // Cover the rest in a second file, then touch only the first: the incremental rebuild compiles one file,
        // and the previews of the other still count, from its record.
        writeFile(
            "src/preview/kotlin/com/acme/ui/MorePreviews.kt",
            """
            package com.acme.ui

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            @Composable
            private fun MissingPreview() {
                Missing()
            }
            """.trimIndent(),
        )
        val covered = runBuild("compilePreviewKotlin")
        assertEquals(TaskOutcome.SUCCESS, covered.task(":compilePreviewKotlin")?.outcome, covered.output)
        writeFile(
            "src/preview/kotlin/com/acme/ui/Previews.kt",
            """
            package com.acme.ui

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.tooling.preview.Preview

            // Touched: only this file is recompiled.
            @Preview
            @Composable
            private fun CoveredPreview() {
                Covered("preview")
            }
            """.trimIndent(),
        )
        val incremental = runBuild("compilePreviewKotlin")
        assertEquals(TaskOutcome.SUCCESS, incremental.task(":compilePreviewKotlin")?.outcome, incremental.output)
        assertFalse(incremental.output.contains("has no preview in this compilation"), incremental.output)
    }

    @Test
    fun `an incremental build of a preview compilation counts the previews and composables of the files it does not compile`() {
        writeSettings()
        writeFile("kotrail.yaml", "severity: warning\nrules:\n  compose.previewRequired: off\n")
        // A warning rather than an error, so that every build succeeds and the class files show what was compiled.
        writeFile("kotrail-preview.yaml", "rules:\n  compose.previewCoverage:\n    enabled: true\n    severity: warning\n    packages: [com.acme.ui]\n    excludeNames: [com.acme.ui.Text]\n")
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
        writeFile(
            "src/main/kotlin/com/acme/ui/Cards.kt",
            """
            package com.acme.ui

            import androidx.compose.runtime.Composable

            @Composable
            fun Text(text: String, content: @Composable () -> Unit = {}) {
                content()
            }

            @Composable
            fun A() {
                Text("a")
            }

            @Composable
            fun B() {
                Text("b")
            }
            """.trimIndent(),
        )
        // A composable of the preview compilation itself, in the listed package: it needs a preview too.
        writeFile(
            "src/preview/kotlin/com/acme/ui/Swatch.kt",
            """
            package com.acme.ui

            import androidx.compose.runtime.Composable

            @Composable
            fun Swatch() {
                Text("swatch")
            }
            """.trimIndent(),
        )
        fun preview(name: String, calls: List<String>) =
            "package com.acme.ui\n\nimport androidx.compose.runtime.Composable\nimport androidx.compose.ui.tooling.preview.Preview\n\n" +
                "@Preview\n@Composable\nprivate fun $name() {\n" + calls.joinToString("") { "    $it\n" } + "}\n"
        writeFile("src/preview/kotlin/com/acme/ui/APreview.kt", preview("APreview", listOf("A()")))
        writeFile("src/preview/kotlin/com/acme/ui/BPreview.kt", preview("BPreview", listOf("B()", "Swatch()")))
        val classes = File(projectDir, "build/classes/kotlin/preview/com/acme/ui")
        val aPreviewClass = File(classes, "APreviewKt.class")
        val bPreviewClass = File(classes, "BPreviewKt.class")
        val swatchClass = File(classes, "SwatchKt.class")
        fun findings(result: BuildResult) = result.output.lines().filter { "has no preview in this compilation" in it }

        val full = runBuild("compilePreviewKotlin")
        assertEquals(TaskOutcome.SUCCESS, full.task(":compilePreviewKotlin")?.outcome, full.output)
        assertTrue(findings(full).isEmpty(), full.output)

        // Only APreview.kt changes. The incremental build compiles it alone; the preview of BPreview.kt still covers B
        // and Swatch, from the record the full build wrote for that file.
        writeFile("src/preview/kotlin/com/acme/ui/APreview.kt", preview("APreview", listOf("A()", "Text(\"edited\")")))
        val untouchedStamps = listOf(bPreviewClass, swatchClass).map { it.lastModified() }
        val edited = runBuild("compilePreviewKotlin")
        assertEquals(TaskOutcome.SUCCESS, edited.task(":compilePreviewKotlin")?.outcome, edited.output)
        assertEquals(untouchedStamps, listOf(bPreviewClass, swatchClass).map { it.lastModified() }, "BPreview.kt or Swatch.kt was compiled again\n" + edited.output)
        assertTrue(findings(edited).isEmpty(), edited.output)

        // BPreview.kt goes. Kotlin compiles no other file for a removed one; the rule had it compile the file it reports
        // on, APreview.kt, again in every incremental build, so B and Swatch, which the record of untouched Swatch.kt
        // declares, are reported there, and the stale record of BPreview.kt is gone.
        File(projectDir, "src/preview/kotlin/com/acme/ui/BPreview.kt").delete()
        val swatchStamp = swatchClass.lastModified()
        val aPreviewStamp = aPreviewClass.lastModified()
        val removed = runBuild("compilePreviewKotlin")
        assertEquals(TaskOutcome.SUCCESS, removed.task(":compilePreviewKotlin")?.outcome, removed.output)
        assertEquals(swatchStamp, swatchClass.lastModified(), "Swatch.kt was compiled again\n" + removed.output)
        assertTrue(aPreviewClass.lastModified() != aPreviewStamp, "APreview.kt was not compiled again\n" + removed.output)
        assertMissingPreviews(findings(removed), removed.output)
        val records = File(projectDir, "build/kotrail/composables/jvm-preview").listFiles().orEmpty().map { it.readText() }
        assertTrue(records.none { "BPreview.kt" in it }, records.joinToString("\n"))

        // Compiling every file again gives the same findings.
        val rerun = runBuild("compilePreviewKotlin", "--rerun-tasks")
        assertEquals(TaskOutcome.SUCCESS, rerun.task(":compilePreviewKotlin")?.outcome, rerun.output)
        assertMissingPreviews(findings(rerun), rerun.output)
    }

    /** B and Swatch are reported, on APreview.kt, and A is not. */
    private fun assertMissingPreviews(findings: List<String>, output: String) {
        assertEquals(2, findings.size, output)
        assertTrue(findings.all { "APreview.kt" in it }, output)
        assertTrue(findings.any { "'com.acme.ui.B' has no preview" in it }, output)
        assertTrue(findings.any { "'com.acme.ui.Swatch' has no preview" in it }, output)
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
                val currentBuilder: ThreadLocal<StringBuilder> = ThreadLocal()

                // Opted out at the site: neither reported here nor recorded for the plugin.
                @Suppress("KOTRAIL_THREAD_LOCAL_IN_UNLOADABLE_CODE")
                val scratchBuilder: ThreadLocal<StringBuilder> = ThreadLocal()
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
        // reads another's output without depending on it. With the configuration cache, since a task action or
        // input that captured the plugin would fail to serialize.
        val result = runBuild("compileKotlin", ":lib:compilePreviewKotlinJvm", ":lib:compileKotlinJs", "--configuration-cache")
        assertEquals(TaskOutcome.SUCCESS, result.task(":lib:compileKotlinJvm")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":lib:compilePreviewKotlinJvm")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":lib:compileKotlinJs")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome, result.output)
        // The rule is off in lib: its ThreadLocal is reported from the plugin module, with the file and line.
        assertFalse(result.output.contains("lib/src/main/kotlin/Buffers.kt:2:5"), result.output)
        assertTrue(result.output.contains("KOTRAIL_OUTBOUND_REFERENCE_IN_BUNDLED_CODE"), result.output)
        assertTrue(result.output.contains("'currentBuilder' (a ThreadLocal) at lib/src/jvmMain/kotlin/Buffers.kt:2,"), result.output)
        assertFalse(result.output.contains("'scratchBuilder'"), result.output)
        assertFalse(result.output.contains("Configuration cache problems"), result.output)
        assertTrue(result.output.contains("KOTRAIL_UNSCOPED_REGISTRATION_IN_UNLOADABLE_CODE"), result.output)
    }

    @Test
    fun `a runtime-only project dependency's records are read after its compile task, by main and by a compilation of its own`() {
        writeHostAndPlugin(
            appBuild = """
                kotlin {
                    // A compilation of its own next to main, which loads the plugin as well.
                    target.compilations.create("docs") {
                        associateWith(target.compilations.getByName("main"))
                    }
                }

                dependencies {
                    // Loaded at runtime only: no compile task of app depends on the plugin's classes.
                    runtimeOnly(project(":plugin"))
                    "docsRuntimeOnly"(project(":plugin"))
                }
            """.trimIndent(),
            pluginBuild = "",
        )
        writeFile("app/src/docs/kotlin/Sample.kt", "class Sample")

        // The plugin's compile task is in the graph too (`build` there), so Gradle validates that app's compile tasks,
        // which read its records, depend on it. With the configuration cache, which stores that wiring.
        val result = runBuild("build", ":app:compileDocsKotlin", "--configuration-cache")
        assertEquals(TaskOutcome.SUCCESS, result.task(":plugin:compileKotlin")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileKotlin")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileDocsKotlin")?.outcome, result.output)
        assertFalse(result.output.contains("implicit dependency"), result.output)
        assertFalse(result.output.contains("Configuration cache problems"), result.output)
        // The rule is on in app and off in the plugin: both of app's compilations report what the plugin recorded.
        assertTrue(result.output.contains("KOTRAIL_OUTBOUND_REFERENCE_IN_BUNDLED_CODE"), result.output)
        val reports = result.output.lines().filter { "'currentBuilder' (a ThreadLocal) at plugin/src/main/kotlin/Buffers.kt:2," in it }
        assertTrue(reports.any { "app/src/main/kotlin/Host.kt" in it }, result.output)
        assertTrue(reports.any { "app/src/docs/kotlin/Sample.kt" in it }, result.output)
    }

    @Test
    fun `bundlesRuntimeClasspath false breaks the task cycle of a runtime-only dependency that compiles against its host`() {
        writeHostAndPlugin(
            appBuild = """
                dependencies {
                    runtimeOnly(project(":plugin"))
                }
            """.trimIndent(),
            pluginBuild = """
                dependencies {
                    // The plugin compiles against the host that loads it.
                    compileOnly(project(":app"))
                }
            """.trimIndent(),
        )

        // app's compile task reads the plugin's records, and the plugin's compile task needs app's classes.
        val cycle = runBuild(":app:compileKotlin", expectFailure = true)
        assertTrue(cycle.output.contains("Circular dependency between the following tasks"), cycle.output)

        // app is the host and is never unloaded, so nothing it loads at runtime is bundled into an unloadable class loader.
        File(projectDir, "app/build.gradle.kts").appendText("\nkotrail {\n    bundlesRuntimeClasspath = false\n}\n")
        val result = runBuild(":app:compileKotlin", ":plugin:compileKotlin", "--configuration-cache")
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileKotlin")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":plugin:compileKotlin")?.outcome, result.output)
        assertFalse(result.output.contains("KOTRAIL_OUTBOUND_REFERENCE_IN_BUNDLED_CODE"), result.output)
    }

    @Test
    fun `a multiplatform library's helper is seen through the metadata by a consumer in another module`() {
        writeSettings(include = listOf("lib", "jsapp"))
        writeFile("kotrail.yaml", "severity: warning\n")
        writeBuild(
            """
            dependencies {
                implementation(project(":lib"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$COROUTINES_VERSION")
            }

            kotrail {
                configFile = file("kotrail.yaml")
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
                jvm()
                js { nodejs() }
                sourceSets {
                    commonMain.dependencies {
                        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$COROUTINES_VERSION")
                    }
                }
            }

            // A published library keeps Kotrail out of its dependencies; the metadata is written all the same.
            kotrail {
                annotations = false
            }
            """.trimIndent(),
        )
        writeFile(
            "jsapp/build.gradle.kts",
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
                js { nodejs() }
                sourceSets {
                    commonMain.dependencies {
                        implementation(project(":lib"))
                        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$COROUTINES_VERSION")
                    }
                }
            }

            kotrail {
                configFile = rootProject.file("kotrail.yaml")
            }
            """.trimIndent(),
        )
        // The helper lives in commonMain and the library has no annotations artifact: the JVM target
        // records the fact in its facts file, the JS target as @InferredStartsAsyncWork against a stub in
        // the klib, and each consumer reads its own kind.
        val client = """
            import kotlinx.coroutines.delay

            class Client(private val reconnector: Reconnector) {
                suspend fun switch() {
                    reconnector.reconnect()
                    delay(500)
                }
            }
        """.trimIndent()
        writeFile("jsapp/src/commonMain/kotlin/Client.kt", client)
        writeFile(
            "lib/src/commonMain/kotlin/Reconnector.kt",
            """
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class Reconnector(private val scope: CoroutineScope) {
                fun reconnect() {
                    scope.launch { }
                }
            }
            """.trimIndent(),
        )
        writeFile("src/main/kotlin/Client.kt", client)

        val result = runBuild("compileKotlin", ":jsapp:compileKotlinJs")

        assertEquals(TaskOutcome.SUCCESS, result.task(":lib:compileKotlinJvm")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":lib:compileKotlinJs")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":jsapp:compileKotlinJs")?.outcome, result.output)
        // Both consumers report the wait: the JVM one from lib's facts file, the JS one from the klib's metadata.
        val findings = result.output.lines().filter { "KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK" in it }
        assertTrue(findings.any { "/src/main/kotlin/Client.kt:6:9" in it }, result.output)
        assertTrue(findings.any { "jsapp/src/commonMain/kotlin/Client.kt:6:9" in it }, result.output)
        assertTrue(result.output.contains("'reconnect' started"), result.output)
        // The library's klib depends on nothing of Kotrail's: the stub is not a dependency.
        val manifest = File(projectDir, "lib/build/classes/kotlin/js/main/default/manifest")
        assertTrue(manifest.isFile, "no klib manifest at ${'$'}manifest\n" + result.output)
        assertFalse(manifest.readText().contains("kotrail"), manifest.readText())
        // The klib carries the annotation; the JVM classes carry nothing of Kotrail, the facts file the fact.
        val klibFiles = File(projectDir, "lib/build/classes/kotlin/js/main").walkTopDown().filter { it.isFile }.toList()
        assertTrue(klibFiles.any { "InferredStartsAsyncWork" in classFileText(it) }, klibFiles.joinToString())
        val jvmClasses = File(projectDir, "lib/build/classes/kotlin/jvm/main")
        assertFalse("com/kitakkun/kotrail" in classFileText(File(jvmClasses, "Reconnector.class")))
        assertFalse(File(jvmClasses, "META-INF/kotrail").exists())
        val facts = factsText(File(projectDir, "lib"), "jvm-main")
        assertTrue(facts.lines().any { "\"id\": \"/Reconnector.reconnect\"" in it && "\"startsAsyncWork\": {}" in it }, facts)
        assertFalse(result.output.contains("Unsupported `compileOnly`"), result.output)
    }

    @Test
    fun `inline functions of a library built without the annotations artifact compile and inline into a consumer`() {
        writeSettings(include = listOf("lib"))
        writeFile("kotrail.yaml", "severity: warning\n")
        writeBuild(
            """
            dependencies {
                implementation(project(":lib"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$COROUTINES_VERSION")
            }

            kotrail {
                configFile = file("kotrail.yaml")
            }
            """.trimIndent(),
        )
        writeFile(
            "lib/build.gradle.kts",
            """
            plugins {
                kotlin("jvm")
                id("com.kitakkun.kotrail")
            }

            repositories {
                maven { url = uri("$repository") }
                mavenCentral()
            }

            kotlin {
                jvmToolchain(21)
            }

            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$COROUTINES_VERSION")
            }

            kotrail {
                annotations = false
                configFile = rootProject.file("kotrail.yaml")
            }
            """.trimIndent(),
        )
        // Inline functions that get a fact (async work, preconditions) written against the stub:
        // public, internal and published, reified, crossinline and noinline, suspend, with
        // defaults, members, inherited and static, inlined within the library and by the consumer.
        writeFile(
            "lib/src/main/kotlin/Inlines.kt",
            """
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            inline fun <T> withLaunch(scope: CoroutineScope, block: () -> T): T {
                scope.launch { }
                return block()
            }

            inline fun later(scope: CoroutineScope, crossinline block: () -> Unit) {
                scope.launch { block() }
            }

            inline fun <reified T> named(count: Int): String {
                require(count > 0)
                return T::class.java.simpleName.repeat(count)
            }

            internal inline fun <T> internalLaunch(scope: CoroutineScope, block: () -> T): T {
                scope.launch { }
                return block()
            }

            internal inline fun internalChecked(count: Int): Int {
                require(count > 0)
                return count * 2
            }

            fun useInternals(scope: CoroutineScope): Int = internalLaunch(scope) { internalChecked(2) }

            suspend inline fun <T> suspendChecked(count: Int, block: () -> T): T {
                require(count > 0)
                return block()
            }

            inline fun defaulted(scope: CoroutineScope, count: Int = 1, block: () -> Unit = {}) {
                require(count > 0)
                scope.launch { }
                block()
            }

            @PublishedApi
            internal inline fun published(scope: CoroutineScope, crossinline block: () -> Unit) {
                scope.launch { block() }
            }

            inline fun viaPublished(scope: CoroutineScope, crossinline block: () -> Unit) = published(scope, block)

            inline fun CoroutineScope.extensionLaunch(noinline block: suspend () -> Unit) {
                launch { block() }
            }

            class Launcher(val scope: CoroutineScope) {
                inline fun <reified T : Any> start(count: Int, crossinline block: (T?) -> Unit) {
                    require(count > 0)
                    scope.launch { block(null) }
                }

                companion object {
                    @JvmStatic
                    inline fun checked(count: Int): Int {
                        require(count > 0)
                        return count
                    }
                }
            }

            open class Base(val scope: CoroutineScope) {
                inline fun baseLaunch(block: () -> Unit) {
                    scope.launch { }
                    block()
                }
            }

            object Launchers {
                inline fun go(scope: CoroutineScope, block: () -> Unit) {
                    scope.launch { }
                    block()
                }
            }
            """.trimIndent(),
        )
        writeFile(
            "src/main/kotlin/Client.kt",
            """
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.delay

            class Derived(scope: CoroutineScope) : Base(scope) {
                fun go() = baseLaunch { }
            }

            class Client(private val scope: CoroutineScope) {
                suspend fun switch(): String {
                    val size = withLaunch(scope) { 1 }
                    later(scope) { println(size) }
                    delay(500)
                    suspendChecked(1) { }
                    defaulted(scope)
                    viaPublished(scope) { }
                    scope.extensionLaunch { }
                    Launcher(scope).start<String>(1) { }
                    Launchers.go(scope) { }
                    return named<String>(-1) + useInternals(scope) + Launcher.checked(1)
                }
            }
            """.trimIndent(),
        )

        val result = runBuild("compileKotlin")

        assertEquals(TaskOutcome.SUCCESS, result.task(":lib:compileKotlin")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome, result.output)
        // The facts of the inline functions are in the facts file, the class files name nothing of Kotrail, and the consumer reads them.
        val classes = File(projectDir, "lib/build/classes/kotlin/main")
        for (name in listOf("InlinesKt.class", "Launcher.class", "Base.class", "Launchers.class")) {
            assertFalse("com/kitakkun/kotrail" in classFileText(File(classes, name)), name)
        }
        val facts = factsText(File(projectDir, "lib"), "jvm-main")
        for (id in listOf("/withLaunch", "/later", "/internalLaunch", "/defaulted", "/published", "/extensionLaunch", "/Launcher.start", "/Base.baseLaunch", "/Launchers.go")) {
            assertTrue(facts.lines().any { "\"id\": \"$id\"" in it && "\"startsAsyncWork\"" in it }, "$id\n$facts")
        }
        for (id in listOf("/named", "/internalChecked", "/suspendChecked", "/defaulted", "/Launcher.start", "/Launcher.Companion.checked")) {
            assertTrue(facts.lines().any { "\"id\": \"$id\"" in it && "\"preconditions\"" in it }, "$id\n$facts")
        }
        val findings = result.output.lines()
        assertTrue(findings.any { "Client.kt:12:9" in it && "KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK" in it && "'later' started" in it }, result.output)
        assertTrue(findings.any { "KOTRAIL_PRECONDITION_VIOLATED" in it && "named requires count > 0 (count = -1)" in it }, result.output)
    }

    @Test
    fun `a JVM library's JAR references nothing of Kotrail, its facts file lies beside it, and inferredMetadata false writes none`() {
        writeSettings()
        writeFile("kotrail.yaml", "severity: warning\n")
        writeFile(
            "src/main/kotlin/Manager.kt",
            """
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class Manager(private val scope: CoroutineScope) {
                fun reconnect() {
                    scope.launch { }
                }
            }
            """.trimIndent(),
        )
        val dependencies = """
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$COROUTINES_VERSION")
            }
        """.trimIndent()
        val jar = File(projectDir, "build/libs/consumer.jar")
        val managerClass = File(projectDir, "build/classes/kotlin/main/Manager.class")
        val factsFile = File(projectDir, "build/kotrail/facts/jvm-main/consumer.facts")

        // By default the facts are written to the facts file under build/kotrail/facts, not into a file of the classes
        // or the JAR, and the classes name no class of Kotrail. Outside a build that publishes, their Kotlin metadata
        // carries the facts for incremental compilation, under the plugin id. The consumer rule still ships, for the
        // JARs of earlier versions.
        writeBuild(dependencies + "\n\nkotrail {\n    configFile = file(\"kotrail.yaml\")\n}")
        runBuild("jar")
        assertFalse("com/kitakkun/kotrail" in classFileText(managerClass))
        assertTrue("com.kitakkun.kotrail" in classFileText(managerClass))
        assertFalse(File(projectDir, "build/classes/kotlin/main/META-INF/kotrail").exists())
        val lines = factsFile.readLines()
        assertTrue(lines.firstOrNull()?.startsWith("{\"module\": \"consumer\", \"kotrail\": ") == true, lines.joinToString("\n"))
        assertTrue(lines.any { "\"id\": \"/Manager.reconnect\", \"signature\": \"\", \"facts\": {\"startsAsyncWork\": {}}" in it }, lines.joinToString("\n"))
        // The source path is relative to the build's root directory, so the published file names no path of this machine.
        assertTrue(lines.any { it.startsWith("{\"file\": \"src/main/kotlin/Manager.kt\", ") }, lines.joinToString("\n"))
        assertFalse(lines.any { projectDir.canonicalFile.invariantSeparatorsPath in it || projectDir.invariantSeparatorsPath in it }, lines.joinToString("\n"))
        ZipFile(jar).use { zip ->
            assertFalse(zip.entries().asSequence().any { it.name.startsWith("META-INF/kotrail") }, zip.entries().asSequence().joinToString { it.name })
            val rules = zip.getEntry("META-INF/proguard/kotrail.pro")?.let { entry -> zip.getInputStream(entry).bufferedReader().readText() }
            assertTrue(rules != null && rules.lines().contains("-dontwarn com.kitakkun.kotrail.**"), rules)
            for (entry in zip.entries().asSequence().filter { it.name.endsWith(".class") }) {
                assertFalse("com/kitakkun/kotrail" in String(zip.getInputStream(entry).readBytes(), Charsets.ISO_8859_1), entry.name)
            }
        }

        // With both switches off, there is no facts file (the one of the previous build is gone), no plugin metadata, and
        // the JAR has no rule.
        writeBuild(dependencies + "\n\nkotrail {\n    configFile = file(\"kotrail.yaml\")\n    inferredMetadata = false\n    consumerRules = false\n}")
        runBuild("jar")
        assertFalse("com/kitakkun/kotrail" in classFileText(managerClass))
        assertFalse("com.kitakkun.kotrail" in classFileText(managerClass))
        assertFalse(factsFile.exists(), factsFile.path)
        ZipFile(jar).use { zip ->
            assertNull(zip.getEntry("META-INF/proguard/kotrail.pro"))
            assertFalse(zip.entries().asSequence().any { it.name.startsWith("META-INF/kotrail") })
        }
    }

    @Test
    fun `a change to a library's facts alone recompiles the module that depends on it`() {
        writeSettings(include = listOf("lib"))
        writeFile("kotrail.yaml", "severity: warning\n")
        writeBuild(
            """
            dependencies {
                implementation(project(":lib"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$COROUTINES_VERSION")
            }

            kotrail {
                configFile = file("kotrail.yaml")
            }
            """.trimIndent(),
        )
        writeFile(
            "lib/build.gradle.kts",
            """
            plugins {
                kotlin("jvm")
                id("com.kitakkun.kotrail")
            }

            repositories {
                maven { url = uri("$repository") }
                mavenCentral()
            }

            kotlin {
                jvmToolchain(21)
            }

            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$COROUTINES_VERSION")
            }

            kotrail {
                configFile = rootProject.file("kotrail.yaml")
            }
            """.trimIndent(),
        )
        writeFile(
            "lib/src/main/kotlin/Manager.kt",
            """
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class Manager(private val scope: CoroutineScope) {
                fun reconnect() {
                    scope.launch { }
                }
            }
            """.trimIndent(),
        )
        val withRequire = """
            fun retry(times: Int): Int {
                require(times >= 0)
                return times
            }
        """.trimIndent()
        writeFile("lib/src/main/kotlin/Retry.kt", withRequire)
        writeFile(
            "src/main/kotlin/Client.kt",
            """
            import kotlinx.coroutines.delay

            suspend fun switch(manager: Manager) {
                manager.reconnect()
                delay(500)
            }

            fun again(): Int = retry(-1)
            """.trimIndent(),
        )
        fun preconditionFindings(result: BuildResult) = result.output.lines().filter { "KOTRAIL_PRECONDITION_VIOLATED" in it && "Client.kt" in it }

        // The consumer reads the library's facts file, which lies in lib/build/kotrail/facts, not in its classes.
        val first = runBuild("compileKotlin", ":lib:jar", "--configuration-cache")
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileKotlin")?.outcome, first.output)
        assertTrue(first.output.lines().any { "Client.kt:5:5" in it && "KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK" in it }, first.output)
        assertEquals(1, preconditionFindings(first).size, first.output)
        val factsFile = File(projectDir, "lib/build/kotrail/facts/jvm-main/lib.facts")
        assertTrue(factsFile.readText().contains("\"preconditions\": {\"conditions\": [\"times >= 0\"]}"), factsFile.readText())
        val libClasses = File(projectDir, "lib/build/classes/kotlin/main")
        assertFalse(File(libClasses, "META-INF/kotrail").exists())
        for (name in listOf("Manager.class", "RetryKt.class")) {
            assertFalse("com/kitakkun/kotrail" in classFileText(File(libClasses, name)), name)
        }
        ZipFile(File(projectDir, "lib/build/libs/lib.jar")).use { zip ->
            assertFalse(zip.entries().asSequence().any { it.name.startsWith("META-INF/kotrail") }, zip.entries().asSequence().joinToString { it.name })
        }

        // Only the body changes: the library's ABI is the same, so the consumer's classpath is too, and only its facts
        // input tells it to compile again. The finding goes.
        writeFile("lib/src/main/kotlin/Retry.kt", "fun retry(times: Int): Int {\n    return times\n}\n")
        val second = runBuild("compileKotlin", "--configuration-cache")
        assertEquals(TaskOutcome.SUCCESS, second.task(":lib:compileKotlin")?.outcome, second.output)
        assertEquals(TaskOutcome.SUCCESS, second.task(":compileKotlin")?.outcome, second.output)
        assertTrue(preconditionFindings(second).isEmpty(), second.output)
        assertFalse(factsFile.readText().contains("\"preconditions\""), factsFile.readText())

        // The require comes back, and with it the finding.
        writeFile("lib/src/main/kotlin/Retry.kt", withRequire)
        val third = runBuild("compileKotlin", "--configuration-cache")
        assertEquals(TaskOutcome.SUCCESS, third.task(":compileKotlin")?.outcome, third.output)
        assertEquals(1, preconditionFindings(third).size, third.output)

        // Nothing changed: everything is up to date.
        val fourth = runBuild("compileKotlin", "--configuration-cache")
        assertEquals(TaskOutcome.UP_TO_DATE, fourth.task(":compileKotlin")?.outcome, fourth.output)
    }

    @Test
    fun `an incremental build that compiles only the caller reads the facts of untouched callees from the facts file of the module itself`() {
        writeSettings()
        writeFile("kotrail.yaml", "severity: warning\nrules:\n  noExternalMutation:\n    plainVars: true\n")
        writeBuild(
            """
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$COROUTINES_VERSION")
            }

            kotrail {
                configFile = file("kotrail.yaml")
            }
            """.trimIndent(),
        )
        val calleeWithRequire = """
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.SideEffect
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class Manager(private val scope: CoroutineScope) {
                fun reconnect() {
                    scope.launch { }
                }
            }

            fun retry(times: Int): Int {
                require(times >= 0)
                return times
            }

            fun send(bytes: ByteArray) {
                if (bytes.isEmpty()) return
                println(bytes.size)
            }

            class Settings {
                var name: String = ""

                fun reset() {
                    name = ""
                }
            }

            data class Point(var x: Int)

            @Composable
            fun Tracker() {
                SideEffect { }
            }
        """.trimIndent()
        val caller = """
            import androidx.compose.runtime.Composable
            import kotlinx.coroutines.delay

            suspend fun switch(manager: Manager) {
                manager.reconnect()
                delay(500)
            }

            fun again(): Int = retry(-1)

            fun reconnectNow(manager: Manager) {
                manager.reconnect()
            }

            fun rename(settings: Settings) {
                settings.name = "renamed"
            }

            fun move(point: Point) {
                point.x = 1
            }

            @Composable
            fun Screen() {
                Tracker()
            }

            fun sendNothing() {
                send(byteArrayOf())
            }
        """.trimIndent()
        writeFile("src/main/kotlin/androidx/compose/runtime/Composable.kt", COMPOSABLE_STUB)
        writeFile("src/main/kotlin/androidx/compose/runtime/Effects.kt", SIDE_EFFECT_STUB)
        writeFile("src/main/kotlin/Callee.kt", calleeWithRequire)
        writeFile("src/main/kotlin/Caller.kt", caller)
        val classes = File(projectDir, "build/classes/kotlin/main")
        val callerClass = File(classes, "CallerKt.class")
        val calleeClasses = listOf("CalleeKt", "Manager", "Settings", "Point").map { File(classes, "$it.class") }
        val factsFile = File(projectDir, "build/kotrail/facts/jvm-main/consumer.facts")
        fun delayFindings(result: BuildResult) = result.output.lines().filter { "Caller.kt:6:5" in it && "KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK" in it }
        // Screen only calls Tracker, which only runs an effect: it draws nothing and needs no preview, also when Tracker
        // comes from the classes directory, through its drawsUi fact.
        fun previewFindings(result: BuildResult) = result.output.lines().filter { "KOTRAIL_COMPOSABLE_WITHOUT_PREVIEW" in it }
        fun preconditionFindings(result: BuildResult) = result.output.lines().filter { "Caller.kt" in it && "KOTRAIL_PRECONDITION_VIOLATED" in it }
        fun noOpFindings(result: BuildResult) = result.output.lines().filter { "Caller.kt" in it && "KOTRAIL_CALL_DOES_NOTHING" in it }
        // Settings is a class of this module, which a plain var write from another class is reported against; Point is a
        // data class with no function of its own, a bag of values, which is not.
        fun stateWriteFindings(result: BuildResult) = result.output.lines().filter { "Caller.kt" in it && "KOTRAIL_MUTATED_OUTSIDE_OWNER" in it }
            .also { findings -> assertTrue(findings.none { "'Point'" in it }, result.output) }
        fun callerFindings(result: BuildResult) = result.output.lines().filter { "Caller.kt" in it && "KOTRAIL_" in it }
        fun entry(file: String, id: String, fact: String) = "{\"file\": \"src/main/kotlin/$file\", \"id\": \"$id\", " to fact
        fun assertFacts(vararg expected: Pair<Pair<String, String>, Boolean>) {
            val lines = factsFile.readLines()
            for ((entry, present) in expected) {
                val (prefix, fact) = entry
                assertEquals(present, lines.any { it.startsWith(prefix) && fact in it }, "$prefix $fact\n" + lines.joinToString("\n"))
            }
        }
        val reconnect = entry("Callee.kt", "/Manager.reconnect", "\"startsAsyncWork\": {}")
        val retry = entry("Callee.kt", "/retry", "\"preconditions\": {\"conditions\": [\"times >= 0\"]}")
        val send = entry("Callee.kt", "/send", "\"noOpWhen\": {\"conditions\": [\"bytes.isEmpty()\"]}")
        val tracker = entry("Callee.kt", "/Tracker", "\"drawsUi\": {\"value\": [\"false\"]}")
        // Facts of the caller that come from its callee: reconnectNow starts work because Manager.reconnect does, and
        // Screen draws nothing because Tracker draws nothing.
        val reconnectNow = entry("Caller.kt", "/reconnectNow", "\"startsAsyncWork\": {}")
        val screen = entry("Caller.kt", "/Screen", "\"drawsUi\": {\"value\": [\"false\"]}")

        // A full build: both files are compiled, both findings are reported, and the facts file describes both files.
        val first = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileKotlin")?.outcome, first.output)
        assertEquals(1, delayFindings(first).size, first.output)
        assertEquals(1, preconditionFindings(first).size, first.output)
        assertEquals(1, stateWriteFindings(first).size, first.output)
        assertTrue(previewFindings(first).isEmpty(), first.output)
        assertEquals(1, noOpFindings(first).size, first.output)
        assertFacts(reconnect to true, retry to true, send to true, tracker to true, reconnectNow to true, screen to true)
        val calleeStamps = calleeClasses.map { it.lastModified() }

        // Only the caller changes (a comment at the end keeps the line numbers). The incremental build compiles
        // Caller.kt alone and loads Callee.kt's declarations from the classes directory, without their bodies; their
        // facts come from the module's own facts file, so both findings are still reported, and the caller's own fact
        // that depends on its callee is written again rather than computed against a callee with no facts.
        writeFile("src/main/kotlin/Caller.kt", "$caller\n\n// Touched.\n")
        val callerStamp = callerClass.lastModified()
        val second = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, second.task(":compileKotlin")?.outcome, second.output)
        assertEquals(calleeStamps, calleeClasses.map { it.lastModified() }, "Callee.kt was compiled again\n" + second.output)
        assertTrue(callerClass.lastModified() != callerStamp, "Caller.kt was not compiled again\n" + second.output)
        assertEquals(1, delayFindings(second).size, second.output)
        assertEquals(1, preconditionFindings(second).size, second.output)
        // Settings, loaded from the classes directory, is still a class of this module, not a library's; Point is still a
        // bag, though its generated members come back from the class file without a source that says they are generated.
        assertEquals(1, stateWriteFindings(second).size, second.output)
        // Tracker, loaded from the classes directory, still draws nothing, so Screen still needs no preview.
        assertTrue(previewFindings(second).isEmpty(), second.output)
        // send, loaded from the classes directory, still returns at once for an empty array.
        assertEquals(1, noOpFindings(second).size, second.output)
        assertFacts(reconnect to true, retry to true, send to true, tracker to true, reconnectNow to true, screen to true)

        // Only the callee's body changes, and none of its facts: the incremental build compiles Callee.kt alone. The
        // plugin metadata of its declarations is what it was, so Kotlin sees nothing of Caller.kt's to compile again.
        val calleeReturningOther = calleeWithRequire.replace("    return times\n", "    return times + 0\n")
        writeFile("src/main/kotlin/Callee.kt", calleeReturningOther)
        val callerStampBeforeThird = callerClass.lastModified()
        val third = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, third.task(":compileKotlin")?.outcome, third.output)
        assertEquals(callerStampBeforeThird, callerClass.lastModified(), "Caller.kt was compiled again\n" + third.output)
        assertTrue(callerFindings(third).isEmpty(), third.output)
        assertFacts(reconnect to true, retry to true, send to true, tracker to true, reconnectNow to true, screen to true)

        // The callee's body changes: its require goes, and so does the guard of send. Its ABI is the same, but the
        // precondition and the no-op condition were in the plugin metadata of retry and send, which is gone now: Kotlin
        // counts them as changed and compiles Caller.kt, which calls them, again in the same build. The caller is checked
        // against the callee as it is now, from the class file: the precondition and no-op findings go, the findings that
        // do not depend on them are reported again.
        val calleeWithoutRequire = calleeReturningOther.replace("    require(times >= 0)\n", "").replace("    if (bytes.isEmpty()) return\n", "")
        writeFile("src/main/kotlin/Callee.kt", calleeWithoutRequire)
        val callerStampBeforeFourth = callerClass.lastModified()
        val fourth = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, fourth.task(":compileKotlin")?.outcome, fourth.output)
        assertTrue(callerClass.lastModified() != callerStampBeforeFourth, "Caller.kt was not compiled again\n" + fourth.output)
        assertTrue(preconditionFindings(fourth).isEmpty(), fourth.output)
        assertTrue(noOpFindings(fourth).isEmpty(), fourth.output)
        assertEquals(1, delayFindings(fourth).size, fourth.output)
        assertEquals(1, stateWriteFindings(fourth).size, fourth.output)
        assertTrue(previewFindings(fourth).isEmpty(), fourth.output)
        assertFacts(reconnect to true, retry to false, send to false, tracker to true, reconnectNow to true, screen to true)

        // The require and the guard come back, in the callee's file alone: the caller is compiled again and the findings
        // return.
        writeFile("src/main/kotlin/Callee.kt", calleeReturningOther)
        val callerStampBeforeFifth = callerClass.lastModified()
        val fifth = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, fifth.task(":compileKotlin")?.outcome, fifth.output)
        assertTrue(callerClass.lastModified() != callerStampBeforeFifth, "Caller.kt was not compiled again\n" + fifth.output)
        assertEquals(1, preconditionFindings(fifth).size, fifth.output)
        assertEquals(1, noOpFindings(fifth).size, fifth.output)
        assertEquals(1, delayFindings(fifth).size, fifth.output)
        assertFacts(reconnect to true, retry to true, send to true, tracker to true, reconnectNow to true, screen to true)

        // Compiling every file again gives the same findings: the incremental builds did not drift from a full one.
        val rerun = runBuild("compileKotlin", "--rerun-tasks")
        assertEquals(TaskOutcome.SUCCESS, rerun.task(":compileKotlin")?.outcome, rerun.output)
        assertEquals(1, delayFindings(rerun).size, rerun.output)
        assertEquals(1, preconditionFindings(rerun).size, rerun.output)
        assertEquals(1, stateWriteFindings(rerun).size, rerun.output)
        assertTrue(previewFindings(rerun).isEmpty(), rerun.output)
        assertEquals(1, noOpFindings(rerun).size, rerun.output)
        assertFacts(reconnect to true, retry to true, send to true, tracker to true, reconnectNow to true, screen to true)
    }

    @Test
    fun `an incremental build compiles a composable again when a model class it reaches gains a callback in its own file`() {
        writeSettings()
        writeFile("kotrail.yaml", "severity: warning\n")
        writeBuild("kotrail {\n    configFile = file(\"kotrail.yaml\")\n}")
        writeFile("src/main/kotlin/androidx/compose/runtime/Composable.kt", COMPOSABLE_STUB)
        writeFile(
            "src/main/kotlin/com/acme/ui/Text.kt",
            """
            package com.acme.ui

            import androidx.compose.runtime.Composable

            @Composable
            fun Text(text: String, content: @Composable () -> Unit = {}) {
                content()
            }
            """.trimIndent(),
        )
        // Screen names Model, Model names Details, and Screen never names Details.
        writeFile("src/main/kotlin/Model.kt", "data class Model(val title: String, val details: Details)\n")
        val details = "data class Details(val text: String)\n"
        writeFile("src/main/kotlin/Details.kt", details)
        writeFile(
            "src/main/kotlin/Screen.kt",
            """
            import androidx.compose.runtime.Composable
            import com.acme.ui.Text

            @Composable
            fun Screen(model: Model) {
                Text(model.title)
            }
            """.trimIndent(),
        )
        val screenClass = File(projectDir, "build/classes/kotlin/main/ScreenKt.class")
        fun callbackFindings(result: BuildResult) = result.output.lines().filter { "Screen.kt" in it && "KOTRAIL_CALLBACK_IN_UI_MODEL" in it }

        val first = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileKotlin")?.outcome, first.output)
        assertTrue(callbackFindings(first).isEmpty(), first.output)

        // Model.kt alone is compiled again, with Details loaded from its class file: the digest of Model reads Details the
        // same way from a class file as from source, so it stays the same, and Screen.kt is left alone.
        writeFile("src/main/kotlin/Model.kt", "data class Model(val title: String, val details: Details)\n\n// Touched.\n")
        val screenStampBeforeTouch = screenClass.lastModified()
        val touched = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, touched.task(":compileKotlin")?.outcome, touched.output)
        assertEquals(screenStampBeforeTouch, screenClass.lastModified(), "Screen.kt was compiled again\n" + touched.output)

        // Details gains a callback in its own file. Kotlin compiles Details.kt, then Model.kt, which names Details; the
        // digest of what the model rules read of Model, in its plugin metadata, takes Details in, so Model counts as
        // changed, and Screen.kt, which names Model, is compiled as well, in the same build.
        writeFile("src/main/kotlin/Details.kt", "data class Details(val text: String, val onOpen: () -> Unit)\n")
        val screenStamp = screenClass.lastModified()
        val second = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, second.task(":compileKotlin")?.outcome, second.output)
        assertTrue(screenClass.lastModified() != screenStamp, "Screen.kt was not compiled again\n" + second.output)
        assertEquals(1, callbackFindings(second).size, second.output)
        assertTrue(callbackFindings(second).single().contains("Details.onOpen"), second.output)

        // The callback goes again, and with it the finding.
        writeFile("src/main/kotlin/Details.kt", details)
        val third = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, third.task(":compileKotlin")?.outcome, third.output)
        assertTrue(callbackFindings(third).isEmpty(), third.output)
    }

    @Test
    fun `an incremental build compiles a file again when a name it checked for appears in another file`() {
        writeSettings()
        writeFile("kotrail.yaml", "severity: warning\n")
        writeBuild("kotrail {\n    configFile = file(\"kotrail.yaml\")\n}")
        writeFile("src/main/kotlin/Stamp.kt", "fun stamp(): Long = java.util.Date().time\n")
        val stampClass = File(projectDir, "build/classes/kotlin/main/StampKt.class")
        fun fqnFindings(result: BuildResult) = result.output.lines().filter { "Stamp.kt" in it && "KOTRAIL_FQN_REFERENCE" in it }

        // `java.util.Date` could be imported: nothing else is called Date here.
        val first = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileKotlin")?.outcome, first.output)
        assertEquals(1, fqnFindings(first).size, first.output)

        // Another file of the same package declares a Date: an import of java.util.Date would now clash with it. The
        // rule asked the package for the name by hand, and recorded the question for incremental compilation, so
        // Stamp.kt, untouched and with nothing in it that resolves to the new class, is compiled again.
        writeFile("src/main/kotlin/Date.kt", "class Date\n")
        val stampStamp = stampClass.lastModified()
        val second = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, second.task(":compileKotlin")?.outcome, second.output)
        assertTrue(stampClass.lastModified() != stampStamp, "Stamp.kt was not compiled again\n" + second.output)
        assertTrue(fqnFindings(second).isEmpty(), second.output)

        // The other Date goes: the import would work again, and the finding is back.
        File(projectDir, "src/main/kotlin/Date.kt").delete()
        val third = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, third.task(":compileKotlin")?.outcome, third.output)
        assertEquals(1, fqnFindings(third).size, third.output)
    }

    @Test
    fun `a build that publishes to a repository compiles without the plugin metadata, an ordinary build with it`() {
        writeSettings()
        writeFile("kotrail.yaml", "severity: warning\n")
        writeFile("src/main/kotlin/Retry.kt", RETRY_SOURCE)
        writePublishingBuild("configFile = file(\"kotrail.yaml\")")
        val retryClass = File(projectDir, "build/classes/kotlin/main/RetryKt.class")
        val publishedJar = File(projectDir, "published/com/example/consumer/1.0/consumer-1.0.jar")

        // An ordinary build writes the facts into the class files, for incremental compilation.
        val assemble = runBuild("assemble", "--configuration-cache")
        assertEquals(TaskOutcome.SUCCESS, assemble.task(":compileKotlin")?.outcome, assemble.output)
        assertTrue(carriesPluginMetadata(retryClass.readBytes()), "RetryKt.class carries no plugin metadata")
        assertTrue(jarClassesCarryPluginMetadata(File(projectDir, "build/libs/consumer-1.0.jar")))

        // A build that publishes to a repository compiles again without it, so the published JAR carries nothing of
        // Kotrail.
        val publish = runBuild("publishAllPublicationsToTestRepository", "--configuration-cache")
        assertEquals(TaskOutcome.SUCCESS, publish.task(":compileKotlin")?.outcome, publish.output)
        assertFalse(carriesPluginMetadata(retryClass.readBytes()), "RetryKt.class carries plugin metadata")
        assertTrue(publishedJar.isFile, publishedJar.path)
        assertFalse(jarClassesCarryPluginMetadata(publishedJar))

        // Publishing again, from the entry the configuration cache stored: the answer stored with it still says the
        // build publishes, so there is nothing to compile, and nothing of Kotrail.
        val again = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("publishAllPublicationsToTestRepository", "--configuration-cache")
            .forwardOutput()
            .build()
        assertTrue(again.output.contains("Reusing configuration cache"), again.output)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileKotlin")?.outcome, again.output)
        assertFalse(jarClassesCarryPluginMetadata(publishedJar))

        // The next ordinary build compiles with it again.
        val next = runBuild("assemble", "--configuration-cache")
        assertEquals(TaskOutcome.SUCCESS, next.task(":compileKotlin")?.outcome, next.output)
        assertTrue(carriesPluginMetadata(retryClass.readBytes()), "RetryKt.class carries no plugin metadata")

        // incrementalMetadata = false keeps it out of every build.
        writePublishingBuild("configFile = file(\"kotrail.yaml\")\n    incrementalMetadata = false")
        val off = runBuild("assemble", "--configuration-cache")
        assertEquals(TaskOutcome.SUCCESS, off.task(":compileKotlin")?.outcome, off.output)
        assertFalse(carriesPluginMetadata(retryClass.readBytes()), "RetryKt.class carries plugin metadata")

        // With the metadata on again and no task name counted, the type of the repository publication task still
        // counts: the build keeps the classes as the previous one compiled them, without the metadata, and publishes
        // them so.
        writePublishingBuild("configFile = file(\"kotrail.yaml\")\n    publishingTasks = emptyList()")
        val byType = runBuild("publishAllPublicationsToTestRepository", "--configuration-cache")
        assertEquals(TaskOutcome.UP_TO_DATE, byType.task(":compileKotlin")?.outcome, byType.output)
        assertEquals(TaskOutcome.SUCCESS, byType.task(":publishMavenPublicationToTestRepository")?.outcome, byType.output)
        assertFalse(jarClassesCarryPluginMetadata(publishedJar))
    }

    @Test
    fun `publishing to the local Maven repository keeps the plugin metadata unless publishingTasks counts it`() {
        writeSettings()
        writeFile("kotrail.yaml", "severity: warning\n")
        writeFile("src/main/kotlin/Retry.kt", RETRY_SOURCE)
        writePublishingBuild("configFile = file(\"kotrail.yaml\")")
        val retryClass = File(projectDir, "build/classes/kotlin/main/RetryKt.class")
        val localRepository = File(projectDir, "m2")
        val publishedJar = File(localRepository, "com/example/consumer/1.0/consumer-1.0.jar")
        val publishToMavenLocal = arrayOf("publishToMavenLocal", "-Dmaven.repo.local=${localRepository.absolutePath}", "--configuration-cache")

        val assemble = runBuild("assemble", "--configuration-cache")
        assertEquals(TaskOutcome.SUCCESS, assemble.task(":compileKotlin")?.outcome, assemble.output)
        assertTrue(carriesPluginMetadata(retryClass.readBytes()), "RetryKt.class carries no plugin metadata")

        // Publishing to the local Maven repository is a trial of the build, not a release: the classes stay as the
        // ordinary build compiled them, and the JAR carries the metadata, which names no class.
        val publish = runBuild(*publishToMavenLocal)
        assertEquals(TaskOutcome.UP_TO_DATE, publish.task(":compileKotlin")?.outcome, publish.output)
        assertTrue(publishedJar.isFile, publishedJar.path)
        assertTrue(jarClassesCarryPluginMetadata(publishedJar))

        // So the next ordinary build has nothing to compile either.
        val next = runBuild("assemble", "--configuration-cache")
        assertEquals(TaskOutcome.UP_TO_DATE, next.task(":compileKotlin")?.outcome, next.output)

        // publishingTasks = listOf("publish*") counts it as publishing: the build compiles again without the metadata,
        // and the next ordinary build puts it back.
        writePublishingBuild("configFile = file(\"kotrail.yaml\")\n    publishingTasks = listOf(\"publish*\")")
        val counted = runBuild(*publishToMavenLocal)
        assertEquals(TaskOutcome.SUCCESS, counted.task(":compileKotlin")?.outcome, counted.output)
        assertFalse(carriesPluginMetadata(retryClass.readBytes()), "RetryKt.class carries plugin metadata")
        assertFalse(jarClassesCarryPluginMetadata(publishedJar))
        val after = runBuild("assemble", "--configuration-cache")
        assertEquals(TaskOutcome.SUCCESS, after.task(":compileKotlin")?.outcome, after.output)
        assertTrue(carriesPluginMetadata(retryClass.readBytes()), "RetryKt.class carries no plugin metadata")
    }

    @Test
    fun `a published library's facts file is a classifier artifact its module metadata and POM do not mention, which a consumer reads`() {
        val repo = File(projectDir, "repo")
        val settings = { name: String ->
            """
            pluginManagement {
                repositories {
                    maven { url = uri("$repository") }
                    gradlePluginPortal()
                    mavenCentral()
                }
                plugins {
                    id("com.kitakkun.kotrail") version "$kotrailVersion"
                    kotlin("jvm") version "$kotlinVersion"
                }
            }
            rootProject.name = "$name"
            """.trimIndent()
        }
        writeFile("lib/settings.gradle.kts", settings("lib"))
        writeFile(
            "lib/build.gradle.kts",
            """
            plugins {
                kotlin("jvm")
                id("com.kitakkun.kotrail")
                `maven-publish`
            }

            group = "com.example"
            version = "1.0"

            repositories {
                maven { url = uri("$repository") }
                mavenCentral()
            }

            kotlin {
                jvmToolchain(21)
            }

            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$COROUTINES_VERSION")
            }

            kotrail {
                configFile = file("kotrail.yaml")
            }

            publishing {
                publications {
                    create<MavenPublication>("maven") {
                        from(components["java"])
                    }
                }
                repositories {
                    maven {
                        name = "local"
                        url = uri("${repo.invariantSeparatorsPath}")
                    }
                }
            }
            """.trimIndent(),
        )
        writeFile("lib/kotrail.yaml", "severity: warning\n")
        writeFile(
            "lib/src/main/kotlin/Retry.kt",
            """
            package lib

            fun retry(times: Int): Int {
                require(times >= 0)
                return times
            }
            """.trimIndent(),
        )
        runBuildIn(File(projectDir, "lib"), "publishAllPublicationsToLocalRepository")

        val published = File(repo, "com/example/lib/1.0")
        assertTrue(File(published, "lib-1.0.jar").isFile, published.list().orEmpty().joinToString())
        val facts = File(published, "lib-1.0-kotrail-facts.facts")
        assertTrue(facts.isFile, published.list().orEmpty().joinToString())
        assertTrue(facts.readText().contains("\"id\": \"lib/retry\""), facts.readText())
        ZipFile(File(published, "lib-1.0.jar")).use { zip ->
            assertFalse(zip.entries().asSequence().any { it.name.startsWith("META-INF/kotrail") }, zip.entries().asSequence().joinToString { it.name })
        }
        // The file is a plain artifact of the publication: the Gradle module metadata describes no variant of it, and
        // neither the module metadata nor the POM names anything of Kotrail.
        assertTrue(File(published, "lib-1.0.module").isFile, published.list().orEmpty().joinToString())
        assertNoKotrailInMetadata(repo)

        // Two consumers, both of which ask for the artifact with the kotrail-facts classifier: one reads the Gradle
        // module metadata, the other only the POM.
        for ((name, metadata) in listOf("module" to "", "pom" to "metadataSources { mavenPom(); artifact() }")) {
            val app = File(projectDir, name)
            writeFile("$name/settings.gradle.kts", settings(name))
            writeFile(
                "$name/build.gradle.kts",
                """
                plugins {
                    kotlin("jvm")
                    id("com.kitakkun.kotrail")
                }

                repositories {
                    maven {
                        url = uri("${repo.invariantSeparatorsPath}")
                        $metadata
                    }
                    maven { url = uri("$repository") }
                    mavenCentral()
                }

                kotlin {
                    jvmToolchain(21)
                }

                dependencies {
                    implementation("com.example:lib:1.0")
                }

                kotrail {
                    configFile = file("kotrail.yaml")
                }
                """.trimIndent(),
            )
            writeFile("$name/kotrail.yaml", "severity: warning\n")
            writeFile("$name/src/main/kotlin/Client.kt", "import lib.retry\n\nfun again(): Int = retry(-1)\n")
            val result = runBuildIn(app, "compileKotlin", "--info")
            assertTrue(
                result.output.lines().any { "Client.kt:3:" in it && "KOTRAIL_PRECONDITION_VIOLATED" in it },
                "$name\n" + result.output,
            )
            assertTrue(result.output.lines().any { "Kotrail: facts files read:" in it && "lib-1.0-kotrail-facts.facts" in it }, "$name\n" + result.output)
        }
    }

    @Test
    fun `a multiplatform library publishes the facts file of its JVM target by classifier alone, which a consumer reads`() {
        val repo = File(projectDir, "repo")
        val settings = { name: String ->
            """
            pluginManagement {
                repositories {
                    maven { url = uri("$repository") }
                    gradlePluginPortal()
                    mavenCentral()
                }
                plugins {
                    id("com.kitakkun.kotrail") version "$kotrailVersion"
                    kotlin("jvm") version "$kotlinVersion"
                    kotlin("multiplatform") version "$kotlinVersion"
                }
            }
            rootProject.name = "$name"
            """.trimIndent()
        }
        writeFile("lib/settings.gradle.kts", settings("lib"))
        writeFile(
            "lib/build.gradle.kts",
            """
            plugins {
                kotlin("multiplatform")
                id("com.kitakkun.kotrail")
                `maven-publish`
            }

            group = "com.example"
            version = "1.0"

            repositories {
                maven { url = uri("$repository") }
                mavenCentral()
            }

            kotlin {
                jvmToolchain(21)
                jvm()
            }

            kotrail {
                configFile = file("kotrail.yaml")
            }

            publishing {
                repositories {
                    maven {
                        name = "local"
                        url = uri("${repo.invariantSeparatorsPath}")
                    }
                }
            }
            """.trimIndent(),
        )
        writeFile("lib/kotrail.yaml", "severity: warning\n")
        writeFile("lib/src/commonMain/kotlin/Retry.kt", "package lib\n\n$RETRY_SOURCE")
        runBuildIn(File(projectDir, "lib"), "publishAllPublicationsToLocalRepository")

        // The JVM target's publication carries the facts file next to its JAR; the root publication carries none.
        val jvm = File(repo, "com/example/lib-jvm/1.0")
        assertTrue(File(jvm, "lib-jvm-1.0.jar").isFile, jvm.list().orEmpty().joinToString())
        val facts = File(jvm, "lib-jvm-1.0-kotrail-facts.facts")
        assertTrue(facts.isFile, jvm.list().orEmpty().joinToString())
        assertTrue(facts.readText().contains("\"id\": \"lib/retry\""), facts.readText())
        val root = File(repo, "com/example/lib/1.0")
        assertFalse(root.list().orEmpty().any { it.endsWith(".facts") }, root.list().orEmpty().joinToString())
        assertNoKotrailInMetadata(repo)

        // A consumer depends on the root module, which redirects to the JVM target, and reads its facts by classifier.
        val app = File(projectDir, "app")
        writeFile("app/settings.gradle.kts", settings("app"))
        writeFile(
            "app/build.gradle.kts",
            """
            plugins {
                kotlin("jvm")
                id("com.kitakkun.kotrail")
            }

            repositories {
                maven { url = uri("${repo.invariantSeparatorsPath}") }
                maven { url = uri("$repository") }
                mavenCentral()
            }

            kotlin {
                jvmToolchain(21)
            }

            dependencies {
                implementation("com.example:lib:1.0")
            }

            kotrail {
                configFile = file("kotrail.yaml")
            }
            """.trimIndent(),
        )
        writeFile("app/kotrail.yaml", "severity: warning\n")
        writeFile("app/src/main/kotlin/Client.kt", "import lib.retry\n\nfun again(): Int = retry(-1)\n")
        val result = runBuildIn(app, "compileKotlin", "--info")
        assertTrue(result.output.lines().any { "Client.kt:3:" in it && "KOTRAIL_PRECONDITION_VIOLATED" in it }, result.output)
        assertTrue(result.output.lines().any { "Kotrail: facts files read:" in it && "lib-jvm-1.0-kotrail-facts.facts" in it }, result.output)
    }

    @Test
    fun `a library without facts publishes a facts file with the header alone`() {
        val repo = File(projectDir, "repo")
        writeFile(
            "lib/settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    maven { url = uri("$repository") }
                    gradlePluginPortal()
                    mavenCentral()
                }
                plugins {
                    id("com.kitakkun.kotrail") version "$kotrailVersion"
                    kotlin("jvm") version "$kotlinVersion"
                }
            }
            rootProject.name = "lib"
            """.trimIndent(),
        )
        writeFile(
            "lib/build.gradle.kts",
            """
            plugins {
                kotlin("jvm")
                id("com.kitakkun.kotrail")
                `maven-publish`
            }

            group = "com.example"
            version = "1.0"

            repositories {
                maven { url = uri("$repository") }
                mavenCentral()
            }

            kotlin {
                jvmToolchain(21)
            }

            publishing {
                publications {
                    create<MavenPublication>("maven") {
                        from(components["java"])
                    }
                }
                repositories {
                    maven {
                        name = "local"
                        url = uri("${repo.invariantSeparatorsPath}")
                    }
                }
            }
            """.trimIndent(),
        )
        writeFile("lib/src/main/kotlin/Greeting.kt", "package lib\n\nfun greeting(name: String): String = \"Hello, \$name\"\n")
        runBuildIn(File(projectDir, "lib"), "publishAllPublicationsToLocalRepository")

        // The compilation found no fact, and the file it wrote, the header alone, is published like any other.
        val published = File(repo, "com/example/lib/1.0")
        assertTrue(File(published, "lib-1.0.jar").isFile, published.list().orEmpty().joinToString())
        val facts = File(published, "lib-1.0-kotrail-facts.facts")
        assertTrue(facts.isFile, published.list().orEmpty().joinToString())
        val lines = facts.readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size, facts.readText())
        // The compiler's module name is `group:name` on Kotlin 2.4 and the project name on 2.3.
        val header = lines.single()
        assertTrue(
            header.startsWith("{\"module\": \"com.example:lib\", \"kotrail\": ") || header.startsWith("{\"module\": \"lib\", \"kotrail\": "),
            facts.readText(),
        )
    }

    /**
     * Two Kotlin/JVM projects: `:app`, a host with unloadableCode on, and `:plugin`, whose main
     * compilation declares a ThreadLocal with the rule off. [appBuild] and [pluginBuild] end
     * their build scripts.
     */
    private fun writeHostAndPlugin(appBuild: String, pluginBuild: String) {
        writeSettings(include = listOf("app", "plugin"))
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "$kotlinVersion" apply false
                id("com.kitakkun.kotrail") apply false
            }
            """.trimIndent(),
        )
        val common = """
            plugins {
                kotlin("jvm")
                id("com.kitakkun.kotrail")
            }

            repositories {
                maven { url = uri("$repository") }
                mavenCentral()
            }

            kotlin {
                jvmToolchain(21)
            }
        """.trimIndent()
        writeFile("app/build.gradle.kts", "$common\n\nkotrail {\n    configFile = file(\"kotrail.yaml\")\n}\n\n$appBuild\n")
        writeFile("plugin/build.gradle.kts", "$common\n\n$pluginBuild\n")
        writeFile("app/kotrail.yaml", "severity: warning\nrules:\n  unloadableCode: on\n")
        writeFile("app/src/main/kotlin/Host.kt", "class Host")
        writeFile("plugin/src/main/kotlin/Buffers.kt", "object Buffers {\n    val currentBuilder: ThreadLocal<StringBuilder> = ThreadLocal()\n}\n")
    }

    /** Neither the Gradle module metadata nor the POM of anything published to [repo] names Kotrail, in any case. */
    private fun assertNoKotrailInMetadata(repo: File) {
        val metadata = repo.walkTopDown().filter { it.isFile && (it.extension == "module" || it.extension == "pom") }.toList()
        assertTrue(metadata.any { it.extension == "module" } && metadata.any { it.extension == "pom" }, metadata.joinToString())
        for (file in metadata) {
            val text = file.readText()
            assertFalse(text.contains("kotrail", ignoreCase = true), "${file.path}\n$text")
        }
    }

    /** A class file's bytes as text, for looking for the names in its constant pool. */
    private fun classFileText(file: File): String = String(file.readBytes(), Charsets.ISO_8859_1)

    /** The facts file the compilation `<target>-<compilation>` of the project in [projectDirectory] wrote; there is one per compilation. */
    private fun factsText(projectDirectory: File, compilation: String): String {
        val files = File(projectDirectory, "build/kotrail/facts/$compilation").listFiles { file -> file.extension == "facts" }.orEmpty()
        assertEquals(1, files.size, files.joinToString())
        return files.single().readText()
    }

    /** The plugin id is the key of Kotrail's plugin data in the Kotlin metadata, a string of the class file. */
    private fun carriesPluginMetadata(classFile: ByteArray): Boolean = "com.kitakkun.kotrail" in String(classFile, Charsets.ISO_8859_1)

    private fun jarClassesCarryPluginMetadata(jar: File): Boolean = ZipFile(jar).use { zip ->
        zip.entries().asSequence().filter { it.name.endsWith(".class") }.any { carriesPluginMetadata(zip.getInputStream(it).readBytes()) }
    }

    /**
     * A Kotlin/JVM build that publishes the `java` component as `com.example:consumer:1.0`, to the
     * local Maven repository or to the repository `test` in `published/`, with [kotrailBlock] in
     * its `kotrail { }` block.
     */
    private fun writePublishingBuild(kotrailBlock: String) {
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "$kotlinVersion"
                id("com.kitakkun.kotrail")
                `maven-publish`
            }

            group = "com.example"
            version = "1.0"

            repositories {
                maven { url = uri("$repository") }
                mavenCentral()
            }

            kotlin {
                jvmToolchain(21)
            }

            publishing {
                publications {
                    create<MavenPublication>("maven") {
                        from(components["java"])
                    }
                }
                repositories {
                    maven {
                        name = "test"
                        url = uri("published")
                    }
                }
            }

            """.trimIndent() + "\nkotrail {\n    $kotrailBlock\n}\n",
        )
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

    private fun runBuild(vararg arguments: String, expectFailure: Boolean = false): BuildResult =
        runBuildIn(projectDir, *arguments, expectFailure = expectFailure)

    private fun runBuildIn(directory: File, vararg arguments: String, expectFailure: Boolean = false): BuildResult {
        val runner = GradleRunner.create()
            .withProjectDir(directory)
            // The artifacts under test are republished under the same snapshot version on every
            // build, so cached metadata would otherwise resolve a previous run's JAR.
            .withArguments(*arguments, "--refresh-dependencies")
            .forwardOutput()
        return if (expectFailure) runner.buildAndFail() else runner.build()
    }

    private companion object {
        const val COROUTINES_VERSION = "1.11.0"

        const val RETRY_SOURCE = "fun retry(times: Int): Int {\n    require(times >= 0)\n    return times\n}\n"

        // A violation of a rule that behaves the same on every supported Kotlin version, so that
        // these tests are about the Gradle wiring rather than about a language feature.
        val NOT_NULL_ASSERTION_VIOLATION = """
            class Cases {
                fun length(name: String?): Int = name!!.length
            }
        """.trimIndent()

        // The annotations the Compose rules look for, by their real names, and a Text that draws: a
        // stub in this compilation is judged by its body, and invoking a composable slot counts.
        val COMPOSABLE_STUB = """
            package androidx.compose.runtime

            @Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
            annotation class Composable
        """.trimIndent()

        val REMEMBER_STUB = """
            package androidx.compose.runtime

            @Composable
            fun <T> remember(key1: Any?, calculation: () -> T): T = calculation()

            @Composable
            fun <T> remember(vararg keys: Any?, calculation: () -> T): T = calculation()
        """.trimIndent()

        // Constant keys of remember: one between two changing keys, and one alone.
        val REDUNDANT_KEY_CASES = """
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.remember

            object Registry

            @Composable
            fun middleKey(id: String, page: Int): String = remember(id, Registry, page) { id + page }

            @Composable
            fun onlyKey(id: String): String = remember(Registry) { id }
        """.trimIndent()

        val SIDE_EFFECT_STUB = """
            package androidx.compose.runtime

            @Composable
            fun SideEffect(effect: () -> Unit) {
                effect()
            }
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

        // Four helpers of one caller, written one after another past an unrelated function: one finding, moved as a block.
        val FAR_RUN_CASES = """
            fun navigationBar(): String = homeIcon() + searchIcon() + inboxIcon() + profileIcon()

            fun beforeNavigationIcons(value: Int): String {
                val first = value + 1
                val second = value + 2
                val third = value + 3
                val fourth = value + 4
                val fifth = value + 5
                return "${'$'}first ${'$'}second ${'$'}third ${'$'}fourth ${'$'}fifth"
            }

            private fun homeIcon(): String = "home"

            private fun searchIcon(): String = "search"

            private fun inboxIcon(): String = "inbox"

            private fun profileIcon(): String = "profile"
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

            fun transform(value: Int): Int = value + 1

            fun combine(acc: Int, value: Int): Int = acc + value

            fun compute(): Int = 42

            class User(val name: String)

            class Repository {
                fun save(user: User) {}
            }

            fun referenceInsideParentheses(list: List<Int>): List<Int> = list.map({ transform(it) })

            fun referenceAfterAnotherArgument(list: List<Int>): Int = list.fold(0, { acc, x -> combine(acc, x) })

            fun referenceAfterArguments(list: List<Int>): Int = list.fold(0) { acc, x -> combine(acc, x) }

            fun referenceAfterEmptyArguments(): Int = run() { compute() }

            fun referenceTypeQualified(users: List<User>): List<String> = users.map { it.name }

            fun referenceBound(users: List<User>, repository: Repository) {
                users.forEach { repository.save(it) }
            }

            fun referenceNamed(list: List<Int>): List<Int> = list.map(transform = { transform(it) })

            fun referenceChained(list: List<Int>): List<Int> = list.map { transform(it) }.map { transform(it) }

            fun referenceNested(list: List<Int>) {
                println(list.map { transform(it) })
            }

            fun referenceMultiline(list: List<Int>): List<Int> = list.map {
                transform(it)
            }

            fun referenceLabeled(list: List<Int>): List<Int> = list.map step@{ transform(it) }

            fun referenceLabeledInsideParentheses(list: List<Int>): List<Int> = list.map(step@{ transform(it) })

            fun referenceInitializer(list: List<Int>): List<Int> = run {
                val step: (Int) -> Int = { transform(it) }
                list.map(step)
            }

            fun referenceProducer(): () -> Int = run { { compute() } }

            class Swaps {
                private val lock = Any()
                private var ready: String? = null
                private var counter = 0

                fun swapInitializer(frame: String): String? {
                    val replaced = ready.also {
                        ready = frame
                        counter++
                    }
                    return replaced
                }

                fun swapReturn(frame: String): String? {
                    counter++
                    return ready.also { ready = frame }
                }

                fun swapLast(frame: String): String? = synchronized(lock) {
                    ready.also {
                        ready = frame
                    }
                }

                fun swapBranch(frame: String, keep: Boolean): String? = synchronized(lock) {
                    if (keep) {
                        null
                    } else {
                        ready.also {
                            ready = frame
                        }
                    }
                }
            }
        """.trimIndent()
    }
}

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

                // Opted out at the site: neither reported here nor recorded for the plugin.
                @Suppress("KOTRAIL_THREAD_LOCAL_IN_UNLOADABLE_CODE")
                val scratch: ThreadLocal<StringBuilder> = ThreadLocal()
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
        assertTrue(result.output.contains("'current' (a ThreadLocal) at lib/src/jvmMain/kotlin/Buffers.kt:2,"), result.output)
        assertFalse(result.output.contains("'scratch'"), result.output)
        assertFalse(result.output.contains("Configuration cache problems"), result.output)
        assertTrue(result.output.contains("KOTRAIL_UNSCOPED_REGISTRATION_IN_UNLOADABLE_CODE"), result.output)
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
    fun `a JVM library's JAR carries nothing of Kotrail, its facts file lies beside it, and inferredMetadata false writes none`() {
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

        // By default the facts are written to the facts file under build/kotrail/facts, not into the classes or the
        // JAR, and the classes name nothing of Kotrail. The consumer rule still ships, for the JARs of earlier versions.
        writeBuild(dependencies + "\n\nkotrail {\n    configFile = file(\"kotrail.yaml\")\n}")
        runBuild("jar")
        assertFalse("com/kitakkun/kotrail" in classFileText(managerClass))
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

        // With both switches off, there is no facts file (the one of the previous build is gone) and the JAR has no rule.
        writeBuild(dependencies + "\n\nkotrail {\n    configFile = file(\"kotrail.yaml\")\n    inferredMetadata = false\n    consumerRules = false\n}")
        runBuild("jar")
        assertFalse("com/kitakkun/kotrail" in classFileText(managerClass))
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

            class Settings {
                var name: String = ""

                fun reset() {
                    name = ""
                }
            }

            data class Point(var x: Int)
        """.trimIndent()
        val caller = """
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
        """.trimIndent()
        writeFile("src/main/kotlin/Callee.kt", calleeWithRequire)
        writeFile("src/main/kotlin/Caller.kt", caller)
        val classes = File(projectDir, "build/classes/kotlin/main")
        val callerClass = File(classes, "CallerKt.class")
        val calleeClasses = listOf("CalleeKt", "Manager", "Settings", "Point").map { File(classes, "$it.class") }
        val factsFile = File(projectDir, "build/kotrail/facts/jvm-main/consumer.facts")
        fun delayFindings(result: BuildResult) = result.output.lines().filter { "Caller.kt:5:5" in it && "KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK" in it }
        fun preconditionFindings(result: BuildResult) = result.output.lines().filter { "Caller.kt" in it && "KOTRAIL_PRECONDITION_VIOLATED" in it }
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
        // A fact of the caller that comes from its callee: reconnectNow starts work because Manager.reconnect does.
        val reconnectNow = entry("Caller.kt", "/reconnectNow", "\"startsAsyncWork\": {}")

        // A full build: both files are compiled, both findings are reported, and the facts file describes both files.
        val first = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileKotlin")?.outcome, first.output)
        assertEquals(1, delayFindings(first).size, first.output)
        assertEquals(1, preconditionFindings(first).size, first.output)
        assertEquals(1, stateWriteFindings(first).size, first.output)
        assertFacts(reconnect to true, retry to true, reconnectNow to true)
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
        assertFacts(reconnect to true, retry to true, reconnectNow to true)

        // Only the callee's body changes: its require goes. Its ABI is the same, so the incremental build compiles
        // Callee.kt alone and leaves Caller.kt as it was compiled: the caller is not checked again, so nothing withdraws
        // the precondition finding the last build reported there (the documented limitation). The facts file has the
        // callee's new facts and keeps the caller's.
        writeFile("src/main/kotlin/Callee.kt", calleeWithRequire.replace("    require(times >= 0)\n", ""))
        val callerStampBeforeThird = callerClass.lastModified()
        val third = runBuild("compileKotlin")
        assertEquals(TaskOutcome.SUCCESS, third.task(":compileKotlin")?.outcome, third.output)
        assertEquals(callerStampBeforeThird, callerClass.lastModified(), "Caller.kt was compiled again\n" + third.output)
        assertTrue(callerFindings(third).isEmpty(), third.output)
        assertFacts(reconnect to true, retry to false, reconnectNow to true)

        // Compiling every file again checks the caller against the callee as it is now: the precondition finding is
        // gone, the finding about the work the callee still starts stays.
        val rerun = runBuild("compileKotlin", "--rerun-tasks")
        assertEquals(TaskOutcome.SUCCESS, rerun.task(":compileKotlin")?.outcome, rerun.output)
        assertEquals(1, delayFindings(rerun).size, rerun.output)
        assertTrue(preconditionFindings(rerun).isEmpty(), rerun.output)
        assertEquals(1, stateWriteFindings(rerun).size, rerun.output)
        assertFacts(reconnect to true, retry to false, reconnectNow to true)
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

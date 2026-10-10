package com.kitakkun.kotrail

import com.kitakkun.kotrail.compose.insets.InsetsSet
import com.kitakkun.kotrail.config.ConfigSchema
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.diagnostics.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The file side of the configuration: the tree's shape, layering, clearing, and the options that patch it. */
@OptIn(ExperimentalCompilerApi::class)
class KotrailConfigTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a rule is a shorthand or a mapping, and settings hang off the rule`() {
        val config = load(
            """
            note: Conventions live in CONTRIBUTING.md.
            exclude: package(com.acme.generated.*)
            fix: false
            generated:
              paths: ["*/gen/*", "*/build/generated/*"]
            rules:
              preferValueClass: off
              commentLength: warning
              compose.windowInsetsUnverifiable: error
              functionLength:
                maxLines: 60
                exclude: name(main)
                note: See ADR-014.
                fix: true
              forbiddenCall:
                functions: [kotlin.io.println, java.util.Date]
                calls:
                  globalScope: fqn(kotlinx.coroutines.launch) && receiver(kotlinx.coroutines.GlobalScope)
              requiredAnnotation:
                policies:
                  screens:
                    where: composable && name(*Screen)
                    annotation: com.acme.Screen
                  entities: class && name(*Entity) -> com.acme.Persisted
              compose.windowInsets:
                known:
                  com.acme.ui.AppScaffold: SystemBars
                  com.acme.ui.AppTopBar: [StatusBars:Top+Horizontal]
                  androidx.compose.material3.Scaffold: none
              compose.compositionLocals:
                known:
                  com.acme.ui.AppTheme:
                    reads: [com.acme.ui.LocalNav]
                    provides:
                      content: [com.acme.ui.LocalPalette]
            """,
        )
        assertFalse(config.isEnabled(KotrailRule.PREFER_VALUE_CLASS))
        assertTrue(config.isEnabled(KotrailRule.COMMENT_LENGTH))
        assertEquals(Severity.WARNING, config.severity(KotrailRule.COMMENT_LENGTH))
        assertEquals(Severity.ERROR, config.severity(KotrailRule.COMPOSE_WINDOW_INSETS_UNVERIFIABLE))
        assertEquals(60, config.functionLength.maxLines)
        assertEquals(" See ADR-014.", config.note(KotrailRule.FUNCTION_LENGTH))
        assertEquals(" Conventions live in CONTRIBUTING.md.", config.note(KotrailRule.PREFER_VALUE_CLASS))
        assertTrue(config.excludes.isConfiguredFor(KotrailRule.FUNCTION_LENGTH))
        assertTrue(config.fixEnabled(KotrailRule.FUNCTION_LENGTH))
        assertFalse(config.fixEnabled(KotrailRule.PREFER_VALUE_CLASS))
        assertTrue(config.generated.matchesPath("/repo/app/gen/Foo.kt"))
        assertTrue(config.generated.matchesPath("C:\\repo\\app\\build\\generated\\ksp\\Foo.kt"))
        assertFalse(config.generated.matchesPath("/repo/app/src/main/Foo.kt"))
        assertTrue(config.generated.matchesAnnotations(setOf("javax.annotation.processing.Generated")))
        assertEquals(listOf("kotlin.io.println", "java.util.Date", "globalScope"), config.forbiddenCall.entries.map { it.name })
        assertEquals(listOf("entities", "screens"), config.requiredAnnotations.map { it.name })
        assertEquals("com.acme.Persisted", config.requiredAnnotations.single { it.name == "entities" }.annotation)
        assertEquals(InsetsSet.fromTypeName("SystemBars"), config.compose.knownInsetsHandlers["com.acme.ui.AppScaffold"])
        assertEquals(InsetsSet.EMPTY, config.compose.knownInsetsHandlers["androidx.compose.material3.Scaffold"])
        val theme = config.compose.compositionLocals.known.getValue("com.acme.ui.AppTheme")
        assertEquals(setOf("com.acme.ui.LocalNav"), theme.reads)
        assertEquals(mapOf("content" to setOf("com.acme.ui.LocalPalette")), theme.provides)
    }

    @Test
    fun `the default generated paths cover Gradle's generated sources and Swift export's bridges`() {
        val generated = load().generated
        assertTrue(generated.matchesPath("/repo/app/build/generated/ksp/main/kotlin/Foo.kt"))
        assertTrue(generated.matchesPath("/x/build/SwiftExport/iosArm64/Debug/Bridge.kt"))
        assertTrue(generated.matchesPath("C:\\x\\build\\SwiftExport\\iosArm64\\Debug\\files\\Bridge.kt"))
        assertFalse(generated.matchesPath("/repo/app/src/iosMain/kotlin/SwiftExport.kt"))
    }

    @Test
    fun `a later file merges key by key and a null takes an entry away`() {
        val config = load(
            """
            exclude: package(com.acme.generated.*)
            rules:
              functionLength:
                maxLines: 60
                exclude: name(main)
              visibilityPolicy:
                private: name(*Preview)
              requiredAnnotation:
                policies:
                  screens: composable -> com.acme.Screen
                  entities: class -> com.acme.Persisted
              compose.windowInsets:
                known:
                  com.acme.ui.AppScaffold: SystemBars
            """,
            """
            exclude: ~
            rules:
              functionLength:
                maxLines: 120
              visibilityPolicy:
                private: ~
              requiredAnnotation:
                policies:
                  screens: ~
                  entities: class -> com.acme.Entity
              compose.windowInsets:
                known:
                  com.acme.ui.AppScaffold: ~
            """,
        )
        assertFalse(config.excludes.isConfiguredFor(KotrailRule.PREFER_VALUE_CLASS))
        assertTrue(config.excludes.isConfiguredFor(KotrailRule.FUNCTION_LENGTH))
        assertEquals(120, config.functionLength.maxLines)
        assertTrue(config.visibilityPolicy.isEmpty)
        assertEquals(listOf("entities"), config.requiredAnnotations.map { it.name })
        assertEquals("com.acme.Entity", config.requiredAnnotations.single().annotation)
        assertTrue(config.compose.knownInsetsHandlers.isEmpty())
    }

    @Test
    fun `options patch the tree the way a later file would`() {
        val config = load(
            listOf("rules:\n  functionLength:\n    maxLines: 60\n  forbiddenCall:\n    calls:\n      a: fqn(a)\n      b: fqn(b)\n"),
            listOf(
                "rules.functionLength=warning",
                "rules.functionLength.maxLines=120",
                "rules.forbiddenCall.calls=b=",
                "rules.forbiddenCall.calls=c=fqn(c)",
                "rules.compose.nesting.maxDepth=2",
                "test.annotations=org.junit.Test, kotlin.test.Test",
            ),
        )
        assertEquals(Severity.WARNING, config.severity(KotrailRule.FUNCTION_LENGTH))
        assertEquals(120, config.functionLength.maxLines)
        assertEquals(listOf("a", "c"), config.forbiddenCall.entries.map { it.name })
        assertEquals(2, config.compose.maxNesting)
        assertEquals(listOf("org.junit.Test", "kotlin.test.Test"), config.test.annotations)
    }

    @Test
    fun `named predicates are shared by every policy and declared once`() {
        val config = load(
            """
            predicates:
              screen: composable && name(*Screen)
              preview: composable && annotated(com.acme.Preview)
              blocking: fqn(kotlinx.coroutines.runBlocking)
            rules:
              visibilityPolicy:
                private: preview
              requiredAnnotation:
                policies:
                  screens: screen -> com.acme.Route
              forbiddenCall:
                calls:
                  noBlocking: blocking
            """,
            """
            predicates:
              preview: ~
            rules:
              visibilityPolicy:
                private: screen
            """,
        )
        val screens = config.requiredAnnotations.single()
        assertEquals("screens", screens.name)
        assertTrue(screens.predicate.toString().startsWith("screen (= "), screens.predicate.toString())
        assertTrue(config.visibilityPolicy.private.toString().startsWith("screen (= "), config.visibilityPolicy.private.toString())
        assertTrue(config.forbiddenCall.entries.single().predicate.toString().startsWith("blocking (= "), config.forbiddenCall.entries.single().predicate.toString())

        assertFails("predicates:\n  composable: annotated(androidx.compose.runtime.Composable)", "kotrail-0.yaml:2:3", "built-in predicate")
        assertFails("predicates:\n  view_model: class", "kotrail-0.yaml:2:3", "letters only")
        assertFails("predicates:\n  screen: composable && nam(*Screen)", "kotrail-0.yaml:2:11", "in the named predicate 'screen'")
        assertFails("predicates:\n  a: b\n  b: a", "kotrail-0.yaml:2:6", "cycle: a -> b -> a")
        assertFails("predicates:\n  screen: fqn(x)\nrules:\n  visibilityPolicy:\n    private: screen", "kotrail-0.yaml:5:14", "unknown predicate 'fqn'")
        val redefined = assertThrows(CliOptionProcessingException::class.java) {
            load(listOf("predicates:\n  screen: composable", "predicates:\n  screen: class"), emptyList())
        }
        assertTrue(redefined.message!!.contains("already defined at kotrail-0.yaml:2:3"), redefined.message)
    }

    @Test
    fun `unknown keys, wrong shapes, and bad values fail with a position`() {
        assertFails("rules:\n  functionLength:\n    maxLine: 60", "kotrail-0.yaml:3:5", "unknown key 'maxLine'")
        assertFails("rules:\n  compose:\n    nesting: off", "kotrail-0.yaml:2:3", "unknown rule 'compose'")
        assertFails("rules:\n  functionLength: sometimes", "kotrail-0.yaml:2:19", "off, on, error, warning")
        assertFails("rules:\n  compose.windowInsetsUnverifiable: off", "", "has no switch")
        assertFails("rules:\n  functionLength:\n    maxLines: sixty", "kotrail-0.yaml:3:15", "must be an integer")
        assertFails("rules:\n  noNotNullAssertion:\n    enabled: no", "", "must be true or false")
        assertFails("rules:\n  forbiddenCall:\n    calls:\n      bad: name(println)", "kotrail-0.yaml:4:12", "unknown call predicate")
        assertFails("functionLength:\n  maxLines: 60", "kotrail-0.yaml:1:1", "unknown key 'functionLength'")
    }

    @Test
    fun `string kinds and APIs are replaced by kinds and apis and extended by their extra settings`() {
        val defaults = load("rules:\n  noAmbiguousStringName: on").noAmbiguousStringName
        assertEquals(KotrailConfig.DEFAULT_STRING_KINDS, defaults.kinds)
        assertEquals(KotrailStringApi("regex", "kotlin.text.toRegex", KotrailStringArgument.Receiver), defaults.apis.single { it.fqName == "kotlin.text.toRegex" })
        assertEquals(KotrailStringArgument.Named("urlString"), defaults.apis.single { it.fqName == "io.ktor.client.request.get" }.argument)

        val extended = load(
            """
            rules:
              noAmbiguousStringName:
                extraKinds:
                  url: [route, url]
                  channel: [channel, topic]
                extraApis:
                  - channel:com.acme.bus.subscribe
                  - sql:com.acme.db.Dao.query#1
                  - url:com.acme.net.fetch#target
            """,
        ).noAmbiguousStringName
        assertEquals(listOf("url", "uri", "endpoint", "link", "href", "route"), extended.kinds["url"])
        assertEquals(listOf("channel", "topic"), extended.kinds["channel"])
        assertEquals(
            listOf(
                KotrailStringApi("channel", "com.acme.bus.subscribe", KotrailStringArgument.Head),
                KotrailStringApi("sql", "com.acme.db.Dao.query", KotrailStringArgument.Index(1)),
                KotrailStringApi("url", "com.acme.net.fetch", KotrailStringArgument.Named("target")),
            ),
            extended.apis.takeLast(3),
        )

        // A replaced table takes the default APIs of the kinds it leaves out with it.
        val urlsOnly = load("rules:\n  noAmbiguousStringName:\n    kinds:\n      url: [url, endpoint]").noAmbiguousStringName
        assertEquals(setOf("url"), urlsOnly.apis.map { it.kind }.toSet())
        assertTrue(urlsOnly.apis.isNotEmpty())

        assertFails("rules:\n  noAmbiguousStringName:\n    extraApis: [dao:com.acme.Dao.find]", "kotrail-0.yaml:3:", "names the kind 'dao'")
        assertFails("rules:\n  noAmbiguousStringName:\n    apis: [com.acme.open]", "kotrail-0.yaml:3:", "must be <kind>:<fully qualified name>")
        assertFails("rules:\n  noAmbiguousStringName:\n    extraApis: [url:com.acme.open#-1]", "kotrail-0.yaml:3:", "must end in #receiver")
        assertFails("rules:\n  noAmbiguousStringName:\n    extraKinds:\n      url: [web-link]", "kotrail-0.yaml:4:", "is not a word")
    }

    @Test
    fun `every option name maps to a schema path`() {
        for ((name, _) in KotrailConfig.OPTION_NAMES) {
            assertTrue(KotrailConfig.optionPath(name) != null, name)
        }
        assertEquals(listOf("rules", "compose.nesting", "maxDepth") to ConfigSchema.Kind.INT, KotrailConfig.optionPath("rules.compose.nesting.maxDepth"))
        assertEquals(listOf("rules", "compose.nesting") to ConfigSchema.Kind.STRING, KotrailConfig.optionPath("rules.compose.nesting"))
        assertTrue(KotrailConfig.optionPath("rules.compose.nesting.maxLines") == null)
    }

    private fun assertFails(yaml: String, position: String, message: String) {
        val failure = assertThrows(CliOptionProcessingException::class.java) { load(yaml) }
        val text = failure.message.orEmpty()
        assertTrue(text.contains(position) && text.contains(message), text)
    }

    private fun load(vararg files: String): KotrailConfig = load(files.map { it.trimIndent() }, emptyList())

    private fun load(files: List<String>, options: List<String>): KotrailConfig {
        val configuration = CompilerConfiguration()
        files.forEachIndexed { index, text ->
            val file = File(dir, "kotrail-$index.yaml")
            file.writeText(text)
            configuration.add(KotrailConfigurationKeys.CONFIG_FILE, file.path)
        }
        options.forEach { configuration.add(KotrailConfigurationKeys.OPTIONS, it) }
        return KotrailConfig.from(configuration)
    }
}

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

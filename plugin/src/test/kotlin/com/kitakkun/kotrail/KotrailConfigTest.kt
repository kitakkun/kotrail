package com.kitakkun.kotrail

import com.kitakkun.kotrail.compose.insets.InsetsSet
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The properties-file side of the configuration: bracketed entry families, layering, and clearing. */
@OptIn(ExperimentalCompilerApi::class, CompilerConfiguration.Internals::class)
class KotrailConfigTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `bracketed entries are read by family and keyed by what is in the brackets`() {
        val config = load(
            """
            requiredAnnotation.policy[screens]=composable && name(*Screen) -> com.acme.Screen
            requiredAnnotation.policy[db.entities]=class && name(*Entity) -> com.acme.Persisted
            compose.windowInsets.known[com.acme.ui.AppScaffold]=SystemBars
            compose.windowInsets.known[androidx.compose.material3.Scaffold]=None
            compose.compositionLocals.known[com.acme.ui.AppTheme]=content:com.acme.ui.LocalPalette, com.acme.ui.LocalNav
            """,
        )
        assertEquals(listOf("db.entities", "screens"), config.requiredAnnotations.map { it.name })
        assertEquals("com.acme.Screen", config.requiredAnnotations.single { it.name == "screens" }.annotation)
        assertEquals(InsetsSet.fromTypeName("SystemBars"), config.compose.knownInsetsHandlers["com.acme.ui.AppScaffold"])
        assertEquals(InsetsSet.EMPTY, config.compose.knownInsetsHandlers["androidx.compose.material3.Scaffold"])
        val theme = config.compose.compositionLocals.known.getValue("com.acme.ui.AppTheme")!!
        assertEquals(setOf("com.acme.ui.LocalNav"), theme.reads)
        assertEquals(mapOf("content" to setOf("com.acme.ui.LocalPalette")), theme.provides)
    }

    @Test
    fun `a later file overrides one entry and an empty value drops it`() {
        val config = load(
            """
            exclude=package(com.acme.generated.*)
            visibilityPolicy.private=name(*Preview)
            requiredAnnotation.policy[screens]=composable -> com.acme.Screen
            requiredAnnotation.policy[entities]=class -> com.acme.Persisted
            compose.windowInsets.known[com.acme.ui.AppScaffold]=SystemBars
            """,
            """
            exclude=
            visibilityPolicy.private=
            requiredAnnotation.policy[screens]=
            requiredAnnotation.policy[entities]=class -> com.acme.Entity
            compose.windowInsets.known[com.acme.ui.AppScaffold]=
            """,
        )
        assertTrue(config.visibilityPolicy.isEmpty)
        assertEquals(listOf("entities"), config.requiredAnnotations.map { it.name })
        assertEquals("com.acme.Entity", config.requiredAnnotations.single().annotation)
        assertTrue("com.acme.ui.AppScaffold" in config.compose.knownInsetsHandlers)
        assertNull(config.compose.knownInsetsHandlers["com.acme.ui.AppScaffold"])
    }

    @Test
    fun `an unknown key, a dotted entry, and a malformed entry fail the build`() {
        assertFails("requiredAnnotation.policy.screens=composable -> com.acme.Screen")
        assertFails("compose.windowInsets.known.com.acme.ui.AppScaffold=SystemBars")
        assertFails("requiredAnnotation.policy[screens]=composable")
        assertFails("compose.windowInsets.known[com.acme.ui.AppScaffold]=Everything")
        assertFails("requiredAnnotation.policy[1st]=composable -> com.acme.Screen")
    }

    private fun assertFails(properties: String) {
        assertThrows(CliOptionProcessingException::class.java) { load(properties) }
    }

    private fun load(vararg files: String): KotrailConfig {
        val configuration = CompilerConfiguration()
        files.forEachIndexed { index, text ->
            val file = File(dir, "kotrail-$index.properties")
            file.writeText(text.trimIndent())
            configuration.add(KotrailConfigurationKeys.CONFIG_FILE, file.path)
        }
        return KotrailConfig.from(configuration)
    }
}

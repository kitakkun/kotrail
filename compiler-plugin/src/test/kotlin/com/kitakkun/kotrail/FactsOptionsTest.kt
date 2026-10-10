package com.kitakkun.kotrail

import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The options that name the facts files: where this compilation writes its own, which ones it reads, and the digest of those. */
@OptIn(ExperimentalCompilerApi::class)
class FactsOptionsTest {
    private val processor = KotrailCommandLineProcessor()

    private fun configuration(vararg options: Pair<String, String>): CompilerConfiguration {
        val configuration = CompilerConfiguration()
        for ((name, value) in options) {
            val option = processor.pluginOptions.single { it.optionName == name }
            processor.processOption(option, value, configuration)
        }
        return configuration
    }

    @Test
    fun `factsOut names the file, facts is split on commas and may repeat, factsHash is accepted and ignored`() {
        val config = KotrailConfig.from(
            configuration(
                "factsOut" to "/build/kotrail/facts/jvm-main/lib.facts",
                "facts" to "/a/one.facts, /b/two.facts",
                "facts" to "/c/three.facts",
                "factsHash" to "0123abcd",
            ),
        )
        assertEquals("/build/kotrail/facts/jvm-main/lib.facts", config.factsOut)
        assertEquals(listOf("/a/one.facts", "/b/two.facts", "/c/three.facts"), config.facts)
    }

    @Test
    fun `without the options nothing is written and nothing is read`() {
        val config = KotrailConfig.from(configuration())
        assertNull(config.factsOut)
        assertEquals(emptyList<String>(), config.facts)
        assertTrue(config.inferredMetadata)
    }

    @Test
    fun `an empty facts value adds nothing`() {
        assertEquals(emptyList<String>(), KotrailConfig.from(configuration("facts" to "")).facts)
    }

    @Test
    fun `facts is the only one of the three that may repeat`() {
        val options = processor.pluginOptions.associateBy { it.optionName }
        assertTrue(options.getValue("facts").allowMultipleOccurrences)
        assertEquals(false, options.getValue("factsOut").allowMultipleOccurrences)
        assertEquals(false, options.getValue("factsHash").allowMultipleOccurrences)
    }
}

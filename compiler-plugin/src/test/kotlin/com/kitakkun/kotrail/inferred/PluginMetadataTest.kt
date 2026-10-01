package com.kitakkun.kotrail.inferred

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The bytes the plugin writes into a declaration's Kotlin metadata for incremental compilation. */
class PluginMetadataTest {
    private val facts = mapOf(
        InferredFactNames.STARTS_ASYNC_WORK to emptyMap(),
        InferredFactNames.NATIVE_ALLOCATION to mapOf("types" to listOf("Bitmap", "returns"), "path" to emptyList()),
        InferredFactNames.PRECONDITIONS to mapOf("conditions" to listOf("times >= 0", "label \"quoted\"\\")),
    )

    @Test
    fun `the same facts give the same bytes whatever order they come in`() {
        val bytes = PluginMetadata.encode(facts)!!
        val reordered = facts.entries.reversed().associate { (name, arguments) -> name to arguments.entries.reversed().associate { it.toPair() } }
        assertArrayEquals(bytes, PluginMetadata.encode(reordered))
        // Names and parameters sorted; values in their own order, which means something.
        assertEquals(
            "{\"nativeAllocation\": {\"path\": [], \"types\": [\"Bitmap\", \"returns\"]}, " +
                "\"preconditions\": {\"conditions\": [\"times >= 0\", \"label \\\"quoted\\\"\\\\\"]}, " +
                "\"startsAsyncWork\": {}}",
            String(bytes, Charsets.UTF_8),
        )
    }

    @Test
    fun `the bytes read back as the facts`() {
        assertEquals(facts, PluginMetadata.decode(PluginMetadata.encode(facts)!!))
    }

    @Test
    fun `a change of a fact changes the bytes, and nothing to say writes nothing`() {
        val changed = facts + (InferredFactNames.PRECONDITIONS to mapOf("conditions" to listOf("times > 0")))
        assertFalse(PluginMetadata.encode(facts)!!.contentEquals(PluginMetadata.encode(changed)!!))
        assertNull(PluginMetadata.encode(emptyMap()))
        assertNull(PluginMetadata.decode("not json".toByteArray()))
    }
}

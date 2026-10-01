package com.kitakkun.kotrail.inferred

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
        val surface = facts + PluginMetadata.surface("0123456789abcdef")
        assertEquals(surface, PluginMetadata.decode(PluginMetadata.encode(surface)!!))
    }

    @Test
    fun `a change of a fact changes the bytes, and nothing to say writes nothing`() {
        val changed = facts + (InferredFactNames.PRECONDITIONS to mapOf("conditions" to listOf("times > 0")))
        assertFalse(PluginMetadata.encode(facts)!!.contentEquals(PluginMetadata.encode(changed)!!))
        assertNull(PluginMetadata.encode(emptyMap()))
        assertNull(PluginMetadata.decode("not json".toByteArray()))
    }

    @Test
    fun `the digest of a surface is stable and changes with any property`() {
        // Lines as ModelSurface writes them: a class, its properties, then a class it reaches.
        val header = "class /Model kind=CLASS data=true value=false modality=FINAL typeParameters= stableMarker=false"
        val name = "  property name public val receiver=- field=true delegated=false compared=true type=kotlin/String function=false slot=false"
        val details = "  property details public val receiver=- field=true delegated=false compared=true type=/Details function=false slot=false"
        val nested = "class /Details kind=CLASS data=true value=false modality=FINAL typeParameters= stableMarker=false"
        val surface = listOf(header, name, details, nested)
        assertEquals(PluginMetadata.digest(surface), PluginMetadata.digest(surface.toList()))
        assertEquals(16, PluginMetadata.digest(surface).length)
        // A property added to the nested class, one that changes its type, or one that becomes a var: another digest.
        val callback = "  property onOpen public val receiver=- field=true delegated=false compared=true type=kotlin/Function0<kotlin/Unit> function=true slot=false"
        assertNotEquals(PluginMetadata.digest(surface), PluginMetadata.digest(surface + callback))
        assertNotEquals(PluginMetadata.digest(surface), PluginMetadata.digest(listOf(header, name.replace("kotlin/String", "kotlin/String?"), details, nested)))
        assertNotEquals(PluginMetadata.digest(surface), PluginMetadata.digest(listOf(header, name.replace(" val ", " var "), details, nested)))
        // The order is part of it: a walk reports the first callback it finds.
        assertNotEquals(PluginMetadata.digest(surface), PluginMetadata.digest(listOf(header, details, name, nested)))
    }
}

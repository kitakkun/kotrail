package com.kitakkun.kotrail.fir.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ComposableManifestTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `entries come back as written, from records whose source still exists`() {
        val source = File(directory, "src/Cards \"quoted\".kt").apply { parentFile.mkdirs(); writeText("") }
        val records = File(directory, "records").path
        ComposableManifest.write(
            records,
            source.path,
            listOf(
                ComposableManifest.Entry("com.acme.ui.Card", "com.acme.ui", "public"),
                ComposableManifest.Entry("com.acme.ui.Row", "com.acme.ui", "internal"),
            ),
        )
        ComposableManifest.write(records, File(directory, "src/Gone.kt").path, listOf(ComposableManifest.Entry("com.acme.ui.Gone", "com.acme.ui", "public")))

        val read = ComposableManifest.read(listOf(records, File(directory, "missing").path))
        assertEquals(listOf("com.acme.ui.Card" to "public", "com.acme.ui.Row" to "internal"), read.map { it.name to it.visibility })
        assertEquals(setOf("com.acme.ui"), read.mapTo(HashSet()) { it.packageName })
    }

    @Test
    fun `a rewritten record replaces the previous one`() {
        val source = File(directory, "Cards.kt").apply { writeText("") }
        val records = File(directory, "records").path
        ComposableManifest.write(records, source.path, listOf(ComposableManifest.Entry("a.Old", "a", "public")))
        ComposableManifest.write(records, source.path, emptyList())
        assertEquals(emptyList<String>(), ComposableManifest.read(listOf(records)).map { it.name })
    }
}

package com.kitakkun.kotrail.fir.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
            emptyList(),
        )
        ComposableManifest.write(records, File(directory, "src/Gone.kt").path, listOf(ComposableManifest.Entry("com.acme.ui.Gone", "com.acme.ui", "public")), emptyList())

        val read = ComposableManifest.read(listOf(records, File(directory, "missing").path))
        assertEquals(listOf("com.acme.ui.Card" to "public", "com.acme.ui.Row" to "internal"), read.map { it.name to it.visibility })
        assertEquals(setOf("com.acme.ui"), read.mapTo(HashSet()) { it.packageName })
    }

    @Test
    fun `a rewritten record replaces the previous one`() {
        val source = File(directory, "Cards.kt").apply { writeText("") }
        val records = File(directory, "records").path
        ComposableManifest.write(records, source.path, listOf(ComposableManifest.Entry("a.Old", "a", "public")), listOf("a.Old"))
        ComposableManifest.write(records, source.path, emptyList(), emptyList())
        assertEquals(emptyList<String>(), ComposableManifest.read(listOf(records)).map { it.name })
        assertEquals(listOf(source.path to emptySet<String>()), ComposableManifest.ownRecords(records).map { it.file to it.previewCallees })
    }

    @Test
    fun `the own records keep each file's previews, and lose the record of a deleted file`() {
        val previews = File(directory, "Previews.kt").apply { writeText("") }
        val gone = File(directory, "Gone.kt").apply { writeText("") }
        val records = File(directory, "records")
        ComposableManifest.write(records.path, previews.path, listOf(ComposableManifest.Entry("a.Frame", "a", "public")), listOf("a.Card", "a.Frame"))
        ComposableManifest.write(records.path, gone.path, emptyList(), listOf("a.Row"))
        gone.delete()

        // Another compilation's directory is only read: the stale record is skipped and stays.
        assertEquals(listOf("a.Frame"), ComposableManifest.read(listOf(records.path)).map { it.name })
        assertEquals(2, records.listFiles().orEmpty().size)

        val own = ComposableManifest.ownRecords(records.path)
        assertEquals(listOf(previews.path), own.map { it.file })
        assertEquals(setOf("a.Card", "a.Frame"), own.single().previewCallees)
        assertEquals(1, records.listFiles().orEmpty().size)
        assertEquals(emptyList<ComposableManifest.Record>(), ComposableManifest.ownRecords(null))
    }

    @Test
    fun `an entry is top-level when its name is its package's`() {
        assertTrue(ComposableManifest.Entry("com.acme.ui.Card", "com.acme.ui", "public").isTopLevel)
        assertFalse(ComposableManifest.Entry("com.acme.ui.Holder.Card", "com.acme.ui", "public").isTopLevel)
        assertTrue(ComposableManifest.Entry("Card", "", "public").isTopLevel)
        assertFalse(ComposableManifest.Entry("Holder.Card", "", "public").isTopLevel)
    }
}

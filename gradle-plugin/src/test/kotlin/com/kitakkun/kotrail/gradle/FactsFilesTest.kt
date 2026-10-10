package com.kitakkun.kotrail.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FactsFilesTest {
    @TempDir
    lateinit var directory: File

    private fun file(path: String, text: String): File = File(directory, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    @Test
    fun `the hash depends on the contents only, not on where the files lie or their order`() {
        val a = file("lib/build/kotrail/facts/jvm-main/lib.facts", "{\"module\": \"lib\"}\n")
        val b = file("cache/other-1.0-kotrail-facts.facts", "{\"module\": \"other\"}\n")
        val movedA = file("elsewhere/a.facts", a.readText())
        val movedB = file("elsewhere/b.facts", b.readText())
        assertEquals(FactsHash.of(listOf(a, b)), FactsHash.of(listOf(movedB, movedA)))
    }

    @Test
    fun `a changed fact changes the hash, and a missing file counts as none`() {
        val a = file("a.facts", "{\"file\": \"A.kt\", \"id\": \"/retry\", \"signature\": \"kotlin/Int\", \"facts\": {\"preconditions\": {\"conditions\": [\"times >= 0\"]}}}\n")
        val before = FactsHash.of(listOf(a))
        a.writeText("")
        assertNotEquals(before, FactsHash.of(listOf(a)))
        assertEquals(FactsHash.of(emptyList()), FactsHash.of(listOf(File(directory, "missing.facts"))))
        assertNotEquals(FactsHash.of(emptyList()), before)
    }

    @Test
    fun `the hash is a SHA-256 in lower-case hex`() {
        assertEquals(64, FactsHash.of(emptyList()).length)
        assertEquals(FactsHash.of(emptyList()), FactsHash.of(emptyList()).lowercase())
    }

    @Test
    fun `the file is named as the compiler plugin names it`() {
        assertEquals("my_lib.facts", FactsFiles.fileName("my lib"))
        assertEquals("lib-core.facts", FactsFiles.fileName("lib-core"))
    }

    @Test
    fun `an Android library publication carries the variant of its component, then of its name, then release`() {
        val variants = setOf("debug", "release", "staging")
        assertEquals("staging", AndroidFactsPublications.libraryVariant("staging", "maven", variants))
        assertEquals("debug", AndroidFactsPublications.libraryVariant(null, "debug", variants))
        assertEquals("debug", AndroidFactsPublications.libraryVariant("custom", "debug", variants))
        assertEquals("release", AndroidFactsPublications.libraryVariant("custom", "maven", variants))
        assertEquals("release", AndroidFactsPublications.libraryVariant(null, "maven", variants))
        assertNull(AndroidFactsPublications.libraryVariant(null, "maven", setOf("debug")))
    }

    @Test
    fun `an androidTarget publication carries its variant, its flavor's release, or release`() {
        val variants = setOf("freeDebug", "freeRelease", "paidRelease")
        assertEquals("freeDebug", AndroidFactsPublications.targetVariant("androidFreeDebug", "android", variants))
        assertEquals("paidRelease", AndroidFactsPublications.targetVariant("androidPaidRelease", "android", variants))
        // Grouped by flavor: one publication per flavor, with the build type as classifier.
        assertEquals("freeRelease", AndroidFactsPublications.targetVariant("androidFree", "android", variants))
        assertNull(AndroidFactsPublications.targetVariant("androidOther", "android", variants))
        assertEquals("release", AndroidFactsPublications.targetVariant("androidStaging", "android", setOf("debug", "release")))
        assertEquals("debug", AndroidFactsPublications.targetVariant("androidDebug", "android", setOf("debug", "release")))
        assertEquals("release", AndroidFactsPublications.targetVariant("android", "android", setOf("debug", "release")))
    }
}

package com.kitakkun.kotrail.fir

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class UnloadableRecordsTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `entries come back with their file and line`() {
        val source = File(directory, "Buffers.kt").apply { writeText("object Buffers {\n    val current = 1\n}\n") }
        val records = File(directory, "jvm-main").path
        UnloadableRecords.begin(records, source.path)
        UnloadableRecords.write(records, source.path, "threadLocal", "current", 21)
        UnloadableRecords.begin(records, File(directory, "Gone.kt").path)
        UnloadableRecords.write(records, File(directory, "Gone.kt").path, "registration", "x", 0)

        val read = UnloadableRecords.read(listOf(records, File(directory, "missing").path))
        assertEquals(listOf("threadLocal" to "current"), read.map { it.kind to it.name })
        assertEquals("${source.path}:2", UnloadableRecords.location(read.single(), rootDir = null))
        assertEquals("Buffers.kt:2", UnloadableRecords.location(read.single(), rootDir = directory.path))
    }
}

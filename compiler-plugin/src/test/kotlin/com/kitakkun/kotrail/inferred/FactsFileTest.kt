package com.kitakkun.kotrail.inferred

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FactsFileTest {
    @TempDir
    lateinit var directory: File

    private fun entry(file: String, id: String, fact: String = InferredFactNames.STARTS_ASYNC_WORK, signature: String = "", arguments: Map<String, List<String>> = emptyMap()) =
        FactsFile.Entry(file, id, signature, mapOf(fact to arguments))

    @Test
    fun `the text is sorted and reads back the same`() {
        val entries = listOf(
            entry("/src/B.kt", "lib/b"),
            entry("/src/A.kt", "lib/Manager.send", signature = "kotlin/Int,kotlin/String"),
            entry("/src/A.kt", "lib/Manager.send", fact = InferredFactNames.PRECONDITIONS, signature = "kotlin/Int,kotlin/String", arguments = mapOf("conditions" to listOf("times >= 0", "label \"quoted\"\\"))),
            entry("/src/A.kt", "lib/Manager.reconnect"),
            entry("/src/A.kt", "lib/Nothing", fact = InferredFactNames.NATIVE_ALLOCATION, arguments = mapOf("types" to listOf("Bitmap", "returns"), "path" to emptyList())),
        )
        val text = FactsFile.render("lib", "1.0", entries)
        assertEquals(
            """
            {"module": "lib", "kotrail": "1.0"}
            {"file": "/src/A.kt", "id": "lib/Manager.reconnect", "signature": "", "facts": {"startsAsyncWork": {}}}
            {"file": "/src/A.kt", "id": "lib/Manager.send", "signature": "kotlin/Int,kotlin/String", "facts": {"preconditions": {"conditions": ["times >= 0", "label \"quoted\"\\"]}, "startsAsyncWork": {}}}
            {"file": "/src/A.kt", "id": "lib/Nothing", "signature": "", "facts": {"nativeAllocation": {"types": ["Bitmap", "returns"], "path": []}}}
            {"file": "/src/B.kt", "id": "lib/b", "signature": "", "facts": {"startsAsyncWork": {}}}

            """.trimIndent(),
            text,
        )
        assertEquals(FactsFile.render("lib", "1.0", entries.reversed()), text)
        assertEquals(text, FactsFile.render("lib", "1.0", FactsFile.parse(text)))
        val send = FactsFile.parse(text).single { it.id == "lib/Manager.send" }
        assertEquals(listOf("times >= 0", "label \"quoted\"\\"), send.facts[InferredFactNames.PRECONDITIONS]?.get("conditions"))
    }

    @Test
    fun `a malformed line is skipped`() {
        val text = "{\"module\": \"lib\"}\nnot json\n{\"file\": \"/A.kt\", \"id\": \"lib/a\", \"signature\": \"\", \"facts\": {\"startsAsyncWork\": {}}}\n{\"file\": \"/A.kt\"\n"
        assertEquals(listOf("lib/a"), FactsFile.parse(text).map { it.id })
    }

    @Test
    fun `an incremental compilation replaces the entries of the files it compiled and drops those of deleted files`() {
        val existing = listOf(
            entry("/src/Kept.kt", "lib/kept"),
            entry("/src/Changed.kt", "lib/changed"),
            entry("/src/Changed.kt", "lib/noLongerStarts"),
            entry("/src/Deleted.kt", "lib/deleted"),
        )
        val fresh = listOf(entry("/src/Changed.kt", "lib/changed", fact = InferredFactNames.PRECONDITIONS, arguments = mapOf("conditions" to listOf("n > 0"))))
        val merged = FactsFile.merge(existing, fresh, compiledFiles = setOf("/src/Changed.kt")) { it != "/src/Deleted.kt" }
        assertEquals(listOf("/src/Changed.kt" to "lib/changed", "/src/Kept.kt" to "lib/kept"), merged.map { it.file to it.id })
        assertEquals(setOf(InferredFactNames.PRECONDITIONS), merged.first().facts.keys)
    }

    @Test
    fun `update merges into the file on disk`() {
        val source = File(directory, "A.kt").apply { writeText("") }
        val file = File(directory, "facts/jvm-main/" + FactsFile.fileName("my lib"))
        assertEquals("my_lib.facts", file.name)
        FactsFile.update(file, "my lib", "1.0", listOf(entry(source.path, "lib/a")), setOf(source.path), rootDir = null)
        assertEquals(listOf("lib/a"), FactsFile.parse(file.readText()).map { it.id })
        assertEquals("{\"module\": \"my lib\", \"kotrail\": \"1.0\"}", file.readLines().first())
    }

    @Test
    fun `a compilation with no entries still writes the header`() {
        val file = File(directory, "facts/jvm-main/" + FactsFile.fileName("lib"))
        FactsFile.update(file, "lib", "1.0", emptyList(), emptySet(), rootDir = null)
        assertEquals("{\"module\": \"lib\", \"kotrail\": \"1.0\"}\n", file.readText())
        assertEquals(emptyList<FactsFile.Entry>(), FactsFile.parse(file.readText()))
    }

    @Test
    fun `a merge that drops every entry writes the header`() {
        val recompiled = File(directory, "A.kt").apply { writeText("") }
        val deleted = File(directory, "B.kt").apply { writeText("") }
        val file = File(directory, "facts/jvm-main/" + FactsFile.fileName("lib"))
        FactsFile.update(file, "lib", "1.0", listOf(entry(recompiled.path, "lib/a"), entry(deleted.path, "lib/b")), setOf(recompiled.path, deleted.path), rootDir = null)
        assertEquals(listOf("lib/a", "lib/b"), FactsFile.parse(file.readText()).map { it.id })

        deleted.delete()
        FactsFile.update(file, "lib", "1.0", emptyList(), setOf(recompiled.path), rootDir = null)
        assertTrue(file.isFile)
        assertEquals("{\"module\": \"lib\", \"kotrail\": \"1.0\"}\n", file.readText())
    }

    @Test
    fun `source paths are relative to the root directory with forward slashes, absolute outside it or without it`() {
        val root = File(directory, "build root")
        val inside = File(root, "lib/src/main/kotlin/A.kt").path
        assertEquals("lib/src/main/kotlin/A.kt", FactsFile.sourcePath(inside, root.path))
        assertEquals(inside, FactsFile.sourcePath(inside, rootDir = null))
        val outside = File(directory, "elsewhere/B.kt").path
        assertEquals(outside, FactsFile.sourcePath(outside, root.path))
        assertEquals(File(root, "lib/src/main/kotlin/A.kt"), FactsFile.sourceFile("lib/src/main/kotlin/A.kt", root.path))
        assertEquals(File(outside), FactsFile.sourceFile(outside, root.path))
    }

    @Test
    fun `an incremental compilation with relative paths replaces a recompiled file and keeps the others that exist`() {
        val root = File(directory, "root")
        val kept = File(root, "lib/Kept.kt").apply { parentFile.mkdirs(); writeText("") }
        val changed = File(root, "lib/Changed.kt").apply { writeText("") }
        val file = File(directory, "lib.facts")
        val rootDir = root.path
        fun path(file: File) = FactsFile.sourcePath(file.path, rootDir)
        FactsFile.update(
            file,
            "lib",
            "1.0",
            listOf(entry(path(kept), "lib/kept"), entry(path(changed), "lib/changed"), entry("lib/Deleted.kt", "lib/deleted")),
            setOf(path(kept), path(changed)),
            rootDir,
        )
        FactsFile.update(file, "lib", "1.0", listOf(entry(path(changed), "lib/changedAgain")), setOf(path(changed)), rootDir)
        assertEquals(
            listOf("lib/Changed.kt" to "lib/changedAgain", "lib/Kept.kt" to "lib/kept"),
            FactsFile.parse(file.readText()).map { it.file to it.id },
        )
    }

    @Test
    fun `the index reads the facts files it is given, the earlier file winning, and skips what it cannot read`() {
        val first = File(directory, "first.facts")
        first.writeText(
            FactsFile.render("first", "1.0", listOf(entry("/A.kt", "lib/a", fact = InferredFactNames.EFFECT_CAPTURE, arguments = mapOf("captured" to listOf("block"))))),
        )
        val second = File(directory, "second.facts")
        second.writeText(
            FactsFile.render(
                "second",
                "1.0",
                listOf(
                    entry("/A.kt", "lib/a", fact = InferredFactNames.EFFECT_CAPTURE, arguments = mapOf("captured" to listOf("other"))),
                    entry("/B.kt", "lib/b", signature = "kotlin/Int"),
                ),
            ),
        )
        // A directory holding a facts file, as the classes directories of earlier versions did, is not read.
        val classes = File(directory, "classes/META-INF/kotrail").apply { mkdirs() }
        File(classes, "old.facts").writeText(FactsFile.render("old", "1.0", listOf(entry("/C.kt", "lib/c"))))
        val index = FactsFileIndex { listOf(first, second, File(directory, "missing.facts"), File(directory, "classes")) }
        assertEquals(mapOf("captured" to listOf("block")), index.lookup("lib/a", "", InferredFactNames.EFFECT_CAPTURE))
        assertEquals(emptyMap<String, List<String>>(), index.lookup("lib/b", "kotlin/Int", InferredFactNames.STARTS_ASYNC_WORK))
        assertNull(index.lookup("lib/b", "", InferredFactNames.STARTS_ASYNC_WORK))
        assertNull(index.lookup("lib/b", "kotlin/Int", InferredFactNames.PRECONDITIONS))
        assertNull(index.lookup("lib/c", "", InferredFactNames.STARTS_ASYNC_WORK))
    }
}

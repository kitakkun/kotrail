package com.kitakkun.kotrail.inferred

import com.kitakkun.kotrail.inferred.FactsFileLookup.Origin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Which facts file answers for a declaration: the module's own for one an incremental compilation did not compile again, the dependencies' for a library. */
class FactsFileLookupTest {
    @TempDir
    lateinit var directory: File

    private val conditions = "conditions"

    private fun entry(file: String, id: String, vararg conditionTexts: String) =
        FactsFile.Entry(file, id, "kotlin/Int", mapOf(InferredFactNames.PRECONDITIONS to mapOf(conditions to conditionTexts.toList())))

    private fun marker(file: String, id: String) = FactsFile.Entry(file, id, "", mapOf(InferredFactNames.STARTS_ASYNC_WORK to emptyMap()))

    private fun factsFile(name: String, vararg entries: FactsFile.Entry): File =
        File(directory, name).apply { writeText(FactsFile.render(name.substringBefore('.'), "1.0", entries.toList())) }

    private fun lookup(dependencies: List<File>, own: File?) =
        FactsFileLookup(FactsFileIndex { dependencies }, FactsFileIndex { listOfNotNull(own) })

    private fun FactsFileLookup.conditionsOf(id: String, origin: Origin): List<String>? =
        lookup(id, "kotlin/Int", InferredFactNames.PRECONDITIONS, origin)?.get(conditions)

    @Test
    fun `a precompiled declaration is read from the module's own file before the dependencies'`() {
        val own = factsFile("app.facts", entry("src/B.kt", "app/retry", "times >= 0"))
        // A dependency that happens to describe the same key loses to the module's own file.
        val dependency = factsFile("lib.facts", entry("lib/src/Retry.kt", "app/retry", "times > 100"))
        val facts = lookup(listOf(dependency), own)
        assertEquals(listOf("times >= 0"), facts.conditionsOf("app/retry", Origin.PRECOMPILED))
    }

    @Test
    fun `a precompiled declaration the own file does not describe falls back to the dependencies'`() {
        val own = factsFile("app.facts", marker("src/B.kt", "app/launcher"))
        val dependency = factsFile("lib.facts", entry("lib/src/Retry.kt", "app/retry", "times >= 0"))
        val facts = lookup(listOf(dependency), own)
        assertEquals(listOf("times >= 0"), facts.conditionsOf("app/retry", Origin.PRECOMPILED))
        // The own file describes the declaration, but not with this fact: the dependencies' are still asked.
        assertNull(facts.lookup("app/launcher", "", InferredFactNames.PRECONDITIONS, Origin.PRECOMPILED))
        assertEquals(emptyMap<String, List<String>>(), facts.lookup("app/launcher", "", InferredFactNames.STARTS_ASYNC_WORK, Origin.PRECOMPILED))
    }

    @Test
    fun `a library declaration is never read from the module's own file`() {
        val own = factsFile("app.facts", entry("src/B.kt", "lib/retry", "times >= 0"))
        val dependency = factsFile("lib.facts", entry("lib/src/Retry.kt", "lib/retry", "times > 100"))
        assertEquals(listOf("times > 100"), lookup(listOf(dependency), own).conditionsOf("lib/retry", Origin.LIBRARY))
        assertNull(lookup(emptyList(), own).conditionsOf("lib/retry", Origin.LIBRARY))
    }

    @Test
    fun `a source declaration is read from no facts file`() {
        val own = factsFile("app.facts", entry("src/B.kt", "app/retry", "times >= 0"))
        val dependency = factsFile("lib.facts", entry("lib/src/Retry.kt", "app/retry", "times > 100"))
        assertNull(lookup(listOf(dependency), own).conditionsOf("app/retry", Origin.SOURCE))
    }

    @Test
    fun `without an own file, or with one not written yet, a precompiled declaration reads the dependencies' only`() {
        val dependency = factsFile("lib.facts", entry("lib/src/Retry.kt", "app/retry", "times > 100"))
        assertEquals(listOf("times > 100"), lookup(listOf(dependency), own = null).conditionsOf("app/retry", Origin.PRECOMPILED))
        assertEquals(listOf("times > 100"), lookup(listOf(dependency), File(directory, "missing.facts")).conditionsOf("app/retry", Origin.PRECOMPILED))
        assertNull(lookup(emptyList(), own = null).conditionsOf("app/retry", Origin.PRECOMPILED))
    }

    @Test
    fun `the writer keeps the entries of the files it did not compile, so the own file reads the same before and after it`() {
        File(directory, "src").mkdirs()
        File(directory, "src/A.kt").writeText("")
        File(directory, "src/B.kt").writeText("")
        val ownFile = factsFile("app.facts", entry("src/A.kt", "app/again", "n > 0"), entry("src/B.kt", "app/retry", "times >= 0"))
        val before = lookup(emptyList(), ownFile)
        assertEquals(listOf("times >= 0"), before.conditionsOf("app/retry", Origin.PRECOMPILED))

        // An incremental compilation of A.kt alone rewrites the file: A.kt's entries are replaced, B.kt's stay.
        FactsFile.update(ownFile, "app", "1.0", listOf(entry("src/A.kt", "app/again", "n > 1")), setOf("src/A.kt"), directory.path)
        val after = lookup(emptyList(), ownFile)
        assertEquals(before.conditionsOf("app/retry", Origin.PRECOMPILED), after.conditionsOf("app/retry", Origin.PRECOMPILED))
        assertEquals(listOf("n > 1"), after.conditionsOf("app/again", Origin.PRECOMPILED))
        // The index read before the write keeps what it read.
        assertEquals(listOf("n > 0"), before.conditionsOf("app/again", Origin.PRECOMPILED))
    }
}

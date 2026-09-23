package com.kitakkun.kotrail.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File
import java.security.MessageDigest

/**
 * Applies the fixes the compiler plugin recorded during the last compilation of each source
 * file, without compiling again. Every Kotlin compilation of the project writes one record per
 * source file under [fixesDirectory] (see `FixRecords` in the compiler plugin): the file's
 * content hash at compile time, then the fixes. A record whose hash does not match the file as
 * it was when this task started is stale (the file changed since it was compiled, or an earlier
 * run fixed it) and is left for the next compilation to refresh.
 *
 * A multiplatform source file has one record per target compilation; records that agree (same
 * hash, same edits) are one set of fixes, applied once. Each fix is applied whole or not at all,
 * and a fix that conflicts with one already taken is left for the next round: it was computed
 * against text that this round changes (see [selectEdits]). Compiling and running `kotrailFix`
 * again picks those up.
 */
@UntrackedTask(because = "It edits the project's sources in place and must run every time it is asked to")
abstract class KotrailFixTask : DefaultTask() {
    /** Where the compilations record their fixes; absent in a project that compiled nothing. */
    @get:Internal
    abstract val fixesDirectory: DirectoryProperty

    @TaskAction
    fun apply() {
        val directory = fixesDirectory.get().asFile
        val records = if (directory.isDirectory) directory.walkTopDown().filter { it.isFile && it.extension == "jsonl" }.sortedBy { it.path }.toList() else emptyList()

        // Every record of one file, keyed by the file; the hash each record was taken against comes along.
        val recordsByFile = linkedMapOf<String, MutableList<Record>>()
        var fixes = 0
        for (recordFile in records) {
            val lines = recordFile.readLines().filter { it.isNotBlank() }
            if (lines.size < 2) continue
            val header = JsonSlurper().parseText(lines.first()) as Map<*, *>
            val recorded = lines.drop(1).map { line ->
                val fix = JsonSlurper().parseText(line) as Map<*, *>
                Fix(
                    (fix["edits"] as List<*>).map { edit ->
                        edit as Map<*, *>
                        Edit((edit["start"] as Number).toInt(), (edit["end"] as Number).toInt(), edit["replacement"] as String)
                    },
                )
            }
            fixes += recorded.size
            recordsByFile.getOrPut(header["file"] as String) { mutableListOf() } += Record(header["hash"] as String, recorded)
        }

        var applied = 0
        var skipped = 0
        var stale = 0
        var changedFiles = 0
        for ((path, fileRecords) in recordsByFile) {
            val file = File(path)
            val currentHash = if (file.isFile) sha256(file.readBytes()) else null
            val current = fileRecords.filter { it.hash == currentHash }
            if (current.isEmpty()) {
                stale++
                continue
            }
            val distinct = current.flatMap { it.fixes }.distinctBy { fix -> fix.edits.map { Triple(it.start, it.end, it.replacement) } }
            val selection = selectEdits(distinct)
            skipped += selection.skippedFixes

            var text = file.readText()
            var changed = false
            for (edit in selection.edits) {
                if (edit.end > text.length) continue
                text = text.substring(0, edit.start) + edit.replacement + text.substring(edit.end)
                applied++
                changed = true
            }
            if (changed) {
                file.writeText(text)
                changedFiles++
            }
        }

        val notes = buildList {
            if (skipped > 0) add("$skipped conflicting ${plural(skipped, "fix", "fixes")} left for the next round")
            if (stale > 0) add("$stale ${plural(stale, "file")} changed since compiled")
        }
        val summary = "Kotrail: applied $applied ${plural(applied, "edit")} in $changedFiles ${plural(changedFiles, "file")}"
        when {
            fixes == 0 -> logger.lifecycle("Kotrail: nothing to fix (compile first if the code has findings)")
            notes.isEmpty() -> logger.lifecycle(summary)
            else -> logger.lifecycle("$summary; ${notes.joinToString("; ")}. Compile and run kotrailFix again for those")
        }
    }

    private fun plural(count: Int, noun: String, plural: String = "${noun}s"): String = if (count == 1) noun else plural

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class Record(val hash: String, val fixes: List<Fix>)
}

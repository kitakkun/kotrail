package com.kitakkun.kotrail.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File
import java.security.MessageDigest

/**
 * Applies the fixes the compiler plugin recorded during the last compilation of each source
 * file, without compiling again. Every Kotlin compilation of the project writes one record per
 * source file under [fixesDirectory] (see `FixRecords` in the compiler plugin): the file's
 * content hash at compile time, then the fixes. A record whose hash no longer matches the file
 * is stale (the file changed since, or was fixed by an earlier run) and is left for the next
 * compilation to refresh.
 *
 * Edits are applied per file from the end backwards, so that earlier offsets stay valid, and an
 * edit that overlaps one already applied is skipped: it was computed against text that has just
 * changed. Compiling and running `kotrailFix` again picks those up.
 */
@UntrackedTask(because = "It edits the project's sources in place and must run every time it is asked to")
abstract class KotrailFixTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixesDirectory: DirectoryProperty

    @TaskAction
    fun apply() {
        val records = fixesDirectory.get().asFile.walkTopDown().filter { it.isFile && it.extension == "jsonl" }.sortedBy { it.path }.toList()
        var applied = 0
        var skipped = 0
        var stale = 0
        var changedFiles = 0
        var fixes = 0
        for (record in records) {
            val lines = record.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val header = JsonSlurper().parseText(lines.first()) as Map<*, *>
            val file = File(header["file"] as String)
            val edits = lines.drop(1).flatMap { line ->
                val fix = JsonSlurper().parseText(line) as Map<*, *>
                (fix["edits"] as List<*>).map { edit ->
                    edit as Map<*, *>
                    Edit((edit["start"] as Number).toInt(), (edit["end"] as Number).toInt(), edit["replacement"] as String)
                }
            }
            if (edits.isEmpty()) continue
            fixes += lines.size - 1
            if (!file.isFile || sha256(file.readBytes()) != header["hash"]) {
                stale++
                continue
            }

            var text = file.readText()
            var lastStart = text.length + 1
            var changed = false
            for (edit in edits.sortedWith(compareByDescending<Edit> { it.start }.thenByDescending { it.end })) {
                if (edit.end > lastStart || edit.end > text.length) {
                    skipped++
                    continue
                }
                text = text.substring(0, edit.start) + edit.replacement + text.substring(edit.end)
                lastStart = edit.start
                applied++
                changed = true
            }
            if (changed) {
                file.writeText(text)
                changedFiles++
            }
        }

        val notes = buildList {
            if (skipped > 0) add("$skipped overlapped edits already applied")
            if (stale > 0) add("$stale files changed since they were compiled")
        }
        when {
            fixes == 0 -> logger.lifecycle("Kotrail: nothing to fix (compile first if the code has findings)")
            notes.isEmpty() -> logger.lifecycle("Kotrail: applied $applied edits in $changedFiles files")
            else -> logger.lifecycle("Kotrail: applied $applied edits in $changedFiles files; ${notes.joinToString("; ")}. Compile and run kotrailFix again for those")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class Edit(val start: Int, val end: Int, val replacement: String)
}

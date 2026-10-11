package com.kitakkun.kotrail.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option
import java.io.File
import java.security.MessageDigest

/**
 * Applies the fixes the compiler plugin recorded during the last compilation of each source
 * file, without compiling again. Every Kotlin compilation of the project writes one record per
 * source file (see `FixRecords` in the compiler plugin): the file's content hash at compile time,
 * then the fixes. It writes each record twice (see `wireFixRecords` in [KotrailGradlePlugin]):
 * under [latestFixesDirectory], where the records of a compilation that failed stay, and under
 * [taskOutputFixesDirectory], which a compilation restored from the build cache brings back.
 *
 * Of a compilation's two records of a file, the latest one is used when its hash matches the file
 * as it was when this task started, and otherwise the task output's when that one's does. The
 * two are never merged: they may come from compilations with different settings. A record whose
 * hash does not match is stale (the file changed since it was compiled, or an earlier run fixed
 * it) and is left for the next compilation to refresh.
 *
 * A multiplatform source file has one record per target compilation; records that agree (same
 * hash, same edits) are one set of fixes, applied once. Each fix is applied whole or not at all,
 * and a fix that conflicts with one already taken is left for the next round: it was computed
 * against text that this round changes (see [selectEdits]). Compiling and running `kotrailFix`
 * again picks those up.
 *
 * [rules] narrows the fixes to those of the named rules (`--rules narrativeOrder,preferVal`, or the
 * `kotrail.fix.rules` Gradle property), each named by its full key (`compose.rememberKeys`) or
 * the part after the last dot (`rememberKeys`); a record written without a rule key matches only
 * when no filter is given (see [isOfRules]). When nothing matches, the output lists the rules that
 * have records. The output names, per file and in total, how many edits of which rule were
 * applied.
 */
@UntrackedTask(because = "It edits the project's sources in place and must run every time it is asked to")
abstract class KotrailFixTask : DefaultTask() {
    /** Where the compilations record their fixes outside every task's outputs; absent in a project that compiled nothing. */
    @get:Internal
    abstract val latestFixesDirectory: DirectoryProperty

    /** Where the compile tasks keep the same records as an output of theirs; absent in a project that compiled nothing. */
    @get:Internal
    abstract val taskOutputFixesDirectory: DirectoryProperty

    /** The project the file paths in the output are relative to. */
    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    /** Comma-separated keys of the rules whose fixes are applied; absent or blank, every rule's. */
    @get:Input
    @get:Optional
    @get:Option(option = "rules", description = "Comma-separated rule keys whose fixes are applied, in full or by the part after the last dot, e.g. narrativeOrder,preferVal (default: all)")
    abstract val rules: Property<String>

    @TaskAction
    fun apply() {
        val selectedRules = rules.orNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        fun isSelected(fix: Fix): Boolean = selectedRules.isEmpty() || fix.isOfRules(selectedRules)
        val latest = readRecords(latestFixesDirectory.get().asFile)
        val taskOutput = readRecords(taskOutputFixesDirectory.get().asFile)
        val keysByFile = (latest.keys + taskOutput.keys).sorted().groupBy { key -> (latest[key] ?: taskOutput.getValue(key)).file }

        val fixesByFile = linkedMapOf<String, List<Fix>>()
        var fixes = 0
        val recordedRules = sortedSetOf<String>()
        var stale = 0
        for ((path, keys) in keysByFile) {
            val file = File(path)
            if (!file.isFile) continue
            val currentHash = sha256(file.readBytes())
            val currentFixes = mutableListOf<Fix>()
            var hasStaleFixes = false
            for (key in keys) {
                val records = listOfNotNull(latest[key], taskOutput[key])
                val current = records.firstOrNull { it.hash == currentHash }
                for (record in if (current == null) records else listOf(current)) {
                    record.fixes.mapNotNullTo(recordedRules) { it.rule }
                    fixes += record.fixes.count(::isSelected)
                }
                if (current != null) {
                    currentFixes += current.fixes.filter(::isSelected)
                } else if (records.any { record -> record.fixes.any(::isSelected) }) {
                    hasStaleFixes = true
                }
            }
            when {
                currentFixes.isNotEmpty() -> fixesByFile[path] = currentFixes
                hasStaleFixes -> stale++
            }
        }

        var applied = 0
        val appliedByRule = linkedMapOf<String, Int>()
        var skipped = 0
        var changedFiles = 0
        for ((path, fileFixes) in fixesByFile) {
            val file = File(path)
            val distinct = fileFixes.distinctBy { fix -> fix.edits.map { Triple(it.start, it.end, it.replacement) } }
            val selection = selectEdits(distinct)
            skipped += selection.skippedFixes

            var text = file.readText()
            val fileByRule = linkedMapOf<String, Int>()
            for (edit in selection.edits) {
                if (edit.end > text.length) continue
                text = text.substring(0, edit.start) + edit.replacement + text.substring(edit.end)
                val rule = selection.fixOf(edit).rule ?: UNKNOWN_RULE
                fileByRule[rule] = (fileByRule[rule] ?: 0) + 1
            }
            if (fileByRule.isNotEmpty()) {
                file.writeText(text)
                changedFiles++
                val fileEdits = fileByRule.values.sum()
                applied += fileEdits
                fileByRule.forEach { (rule, count) -> appliedByRule[rule] = (appliedByRule[rule] ?: 0) + count }
                logger.lifecycle("Kotrail: ${displayPath(file)}: $fileEdits ${plural(fileEdits, "edit")} (${breakdown(fileByRule)})")
            }
        }

        val notes = buildList {
            if (skipped > 0) add("$skipped conflicting ${plural(skipped, "fix", "fixes")} left for the next round")
            if (stale > 0) add("$stale ${plural(stale, "file")} changed since compiled")
        }
        val byRule = if (appliedByRule.isEmpty()) "" else " (${breakdown(appliedByRule)})"
        val summary = "Kotrail: applied $applied ${plural(applied, "edit")} in $changedFiles ${plural(changedFiles, "file")}$byRule"
        when {
            fixes == 0 && selectedRules.isNotEmpty() && recordedRules.isNotEmpty() -> logger.lifecycle(
                "Kotrail: nothing to fix for ${selectedRules.joinToString(", ") { "'$it'" }}; recorded rules: ${recordedRules.joinToString(", ")}",
            )
            fixes == 0 -> {
                val forRules = if (selectedRules.isEmpty()) "" else " for ${selectedRules.joinToString(", ") { "'$it'" }}"
                logger.lifecycle("Kotrail: nothing to fix$forRules (compile first if the code has findings)")
            }
            notes.isEmpty() -> logger.lifecycle(summary)
            else -> logger.lifecycle("$summary; ${notes.joinToString("; ")}. Compile and run kotrailFix again for those")
        }
    }

    private fun plural(count: Int, noun: String, plural: String = "${noun}s"): String = if (count == 1) noun else plural

    /** `preferVal 12, narrativeOrder 16`, in the order the rules were first applied. */
    private fun breakdown(counts: Map<String, Int>): String = counts.entries.joinToString(", ") { (rule, count) -> "$rule $count" }

    private fun displayPath(file: File): String {
        val base = projectDirectory.get().asFile
        return if (file.startsWith(base)) file.relativeTo(base).invariantSeparatorsPath else file.path
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * The records under [directory], by their path relative to it: `jvm-main/<sha1 of the source
     * path>.jsonl` is one compilation's record of one source file, at the same path in either
     * directory.
     */
    private fun readRecords(directory: File): Map<String, Record> {
        if (!directory.isDirectory) return emptyMap()
        return directory.walkTopDown()
            .filter { it.isFile && it.extension == "jsonl" }
            .mapNotNull { recordFile -> readRecord(recordFile)?.let { recordFile.relativeTo(directory).invariantSeparatorsPath to it } }
            .toMap()
    }

    /** The record in [recordFile], or null for an empty file. A record with its header alone has no fixes. */
    private fun readRecord(recordFile: File): Record? {
        val lines = recordFile.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val header = JsonSlurper().parseText(lines.first()) as Map<*, *>
        val fixes = lines.drop(1).map { line ->
            val fix = JsonSlurper().parseText(line) as Map<*, *>
            Fix(
                fix["rule"] as String?,
                (fix["edits"] as List<*>).map { edit ->
                    edit as Map<*, *>
                    Edit((edit["start"] as Number).toInt(), (edit["end"] as Number).toInt(), edit["replacement"] as String)
                },
            )
        }
        return Record(header["file"] as String, header["hash"] as String, fixes)
    }

    /** One compilation's record of [file]: the hash of the file it was taken against, and every fix it holds. */
    private class Record(val file: String, val hash: String, val fixes: List<Fix>)

    private companion object {
        /** How an edit from a record written without a rule key is counted. */
        const val UNKNOWN_RULE = "unknown rule"
    }
}

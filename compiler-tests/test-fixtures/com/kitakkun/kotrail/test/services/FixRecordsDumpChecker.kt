package com.kitakkun.kotrail.test.services

import com.kitakkun.kotrail.test.compat.AfterAnalysisCheckerBase
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions
import org.jetbrains.kotlin.test.services.moduleStructure
import java.io.File

/**
 * Compares the fixes recorded by the modules that ask for them with `// DUMP_KOTRAIL_FIXES` (see
 * [fixesDirFor]) with `<test>.fixes.txt` next to the test data. The dump lists, file by file,
 * each fix as its diagnostic and its edits: a range, or the position of an insertion, as
 * `line:column` (both from 1) in the file as it was compiled, and the replacement as the record
 * writes it. A file without fixes is left out.
 *
 * The dump is compared even after a failure: a diagnostics test whose fixture reports errors
 * stops after the frontend as it expects to, and the framework counts that as one.
 */
class FixRecordsDumpChecker(testServices: TestServices) : AfterAnalysisCheckerBase(testServices) {
    override fun checkAfterAnalysis(thereWereFailures: Boolean) {
        val modules = testServices.moduleStructure.modules.filter { KotrailTestDirectives.DUMP_KOTRAIL_FIXES in it.directives }
        if (modules.isEmpty()) return
        val dump = buildString {
            for (module in modules) {
                append("Module: ${module.name}\n")
                val records = testServices.fixesDirFor(module).listFiles { file -> file.extension == "jsonl" }.orEmpty()
                    .map { it.readLines() }
                    .map { lines -> File(recordedPath(lines.first())) to lines.drop(1) }
                    .sortedBy { (file, _) -> file.name }
                for ((file, fixes) in records) {
                    if (fixes.isEmpty()) continue
                    val text = file.readText()
                    append("  ${file.name}\n")
                    for (fix in fixes) {
                        val diagnostic = DIAGNOSTIC.find(fix)?.groupValues?.get(1) ?: error("no diagnostic in $fix")
                        val edits = EDIT.findAll(fix).joinToString("; ") { edit ->
                            val (start, end, replacement) = edit.destructured
                            val at = if (start == end) text.position(start.toInt()) else "${text.position(start.toInt())}-${text.position(end.toInt())}"
                            "$at $replacement"
                        }
                        append("    $diagnostic: $edits\n")
                    }
                }
            }
        }
        val testData = testServices.moduleStructure.originalTestDataFiles.first()
        testServices.assertions.assertEqualsToFile(File(testData.parentFile, testData.nameWithoutExtension + ".fixes.txt"), dump)
    }

    private fun recordedPath(header: String): String {
        val quoted = FILE.find(header)?.groupValues?.get(1) ?: error("no file in the record header $header")
        return quoted.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    /** `line:column` of [offset], both counted from 1. */
    private fun String.position(offset: Int): String {
        val lineStart = lastIndexOf('\n', offset - 1) + 1
        val line = substring(0, lineStart).count { it == '\n' } + 1
        return "$line:${offset - lineStart + 1}"
    }

    private companion object {
        val FILE = Regex(""""file": "((?:[^"\\]|\\.)*)"""")
        val DIAGNOSTIC = Regex(""""diagnostic": "([^"]*)"""")
        val EDIT = Regex("""\{"start": (\d+), "end": (\d+), "replacement": ("(?:[^"\\]|\\.)*")}""")
    }
}

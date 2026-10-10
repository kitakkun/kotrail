package com.kitakkun.kotrail.test.services

import com.kitakkun.kotrail.test.compat.AfterAnalysisCheckerBase
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions
import org.jetbrains.kotlin.test.services.moduleStructure
import java.io.File

/**
 * Compares the facts files the modules of a box test wrote (see [factsFileFor]) with
 * `<test>.facts.txt` next to the test data: what a module that depends on them reads back. Every
 * module writes one, the header alone when it has no facts, and appears in the dump with its
 * entries, none for such a module. A test none of whose modules has an entry has no such file.
 * The header line, which carries the Kotrail version, is left out after checking that it is
 * there, and each file path is cut down to its part under the framework's temporary source
 * directory.
 */
class FactsFileDumpChecker(testServices: TestServices) : AfterAnalysisCheckerBase(testServices) {
    override fun checkAfterAnalysis(thereWereFailures: Boolean) {
        if (thereWereFailures) return
        var entries = 0
        val dump = buildString {
            for (module in testServices.moduleStructure.modules) {
                val factsFile = testServices.factsFileFor(module).takeIf { it.isFile } ?: continue
                val lines = factsFile.readLines()
                testServices.assertions.assertTrue(lines.firstOrNull()?.startsWith(HEADER_START) == true) {
                    "${factsFile.name} of module ${module.name} does not start with the header: ${lines.firstOrNull()}"
                }
                append("Module: ${module.name} (${factsFile.name})\n")
                lines.drop(1).forEach {
                    entries++
                    append(it.replace(TEMPORARY_SOURCE_ROOT, "\"file\": \"")).append('\n')
                }
            }
        }
        val testData = testServices.moduleStructure.originalTestDataFiles.first()
        val expected = File(testData.parentFile, testData.nameWithoutExtension + ".facts.txt")
        if (entries == 0 && !expected.exists()) return
        testServices.assertions.assertEqualsToFile(expected, dump)
    }

    private companion object {
        const val HEADER_START = "{\"module\": "
        val TEMPORARY_SOURCE_ROOT = Regex(""""file": "[^"]*/kotlin-sources/""")
    }
}

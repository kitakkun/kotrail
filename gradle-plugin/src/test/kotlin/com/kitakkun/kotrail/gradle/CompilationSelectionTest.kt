package com.kitakkun.kotrail.gradle

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompilationSelectionTest {
    private val swiftExport = CompilationSelection.SWIFT_EXPORT_COMPILATION

    @Test
    fun `the Swift export bridge compilation is left alone unless enabled for itself`() {
        assertFalse(CompilationSelection.isApplicable(swiftExport, true, null))
        assertFalse(CompilationSelection.isApplicable(swiftExport, false, null))
        assertFalse(CompilationSelection.isApplicable(swiftExport, true, false))
        assertTrue(CompilationSelection.isApplicable(swiftExport, true, true))
        assertTrue(CompilationSelection.isApplicable(swiftExport, false, true))
    }

    @Test
    fun `other compilations follow the project setting unless an override sets their own`() {
        for (name in listOf("main", "test", "jvmTest", "debug")) {
            assertTrue(CompilationSelection.isApplicable(name, true, null), name)
            assertFalse(CompilationSelection.isApplicable(name, false, null), name)
            assertFalse(CompilationSelection.isApplicable(name, true, false), name)
            assertTrue(CompilationSelection.isApplicable(name, false, true), name)
        }
    }

    @Test
    fun `only the exact name is left alone`() {
        assertTrue(CompilationSelection.isApplicable("swiftExport", true, null))
        assertTrue(CompilationSelection.isApplicable("SwiftExportMain", true, null))
    }
}

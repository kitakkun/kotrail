package com.kitakkun.kotrail.test.services

import java.io.File

/**
 * Per-Kotlin-version test data overrides.
 *
 * Most fixtures are shared by every supported Kotlin version, but a few depend on behavior of the
 * surrounding compiler rather than of Kotrail: standard diagnostics that only exist in one version,
 * or IR dump rendering that changed between releases. Such a fixture is copied into
 * `compiler-tests/testData-<family>/` under the same relative path, and the runner reads the copy
 * instead of the shared one. `-PupdateTestData=true` then rewrites the copy, not the shared file.
 *
 * The overlay is a whole-test override: put the `.kt` file *and* its golden files (`.fir.ir.txt`,
 * …) in the overlay directory, because the framework derives the golden file paths from the source
 * file path it is handed.
 *
 * Test discovery still walks `compiler-tests/testData` only, so an overlay never adds or removes a
 * test — it can only change what an existing test reads.
 */
internal object TestDataOverlay {
    private const val SHARED_ROOT = "compiler-tests/testData"

    /** Set from `compiler-tests/build.gradle.kts`; absent when the active version needs no overrides. */
    private val overlayRoot: String? = System.getProperty("kotrail.test.testDataOverlay")?.takeIf { it.isNotBlank() }

    fun resolve(filePath: String): String {
        val root = overlayRoot ?: return filePath
        val relative = filePath.removePrefix("$SHARED_ROOT/").takeIf { it != filePath } ?: return filePath
        val candidate = File(root, relative)
        return if (candidate.isFile) candidate.path else filePath
    }
}

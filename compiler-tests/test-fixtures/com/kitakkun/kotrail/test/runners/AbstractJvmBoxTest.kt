package com.kitakkun.kotrail.test.runners

import com.kitakkun.kotrail.test.compat.BoxTestBase
import com.kitakkun.kotrail.test.services.TestDataOverlay
import com.kitakkun.kotrail.test.services.configureKotrail
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.directives.TestPhaseDirectives.RUN_PIPELINE_TILL
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.TestPhase

/**
 * Box tests: `fun box(): String` must return `"OK"` in `testData/box`. These run the full
 * pipeline, so multi-module fixtures exercise the IR metadata writer on the dependency module
 * and the metadata reader on the dependent module.
 */
open class AbstractJvmBoxTest : BoxTestBase(FirParser.LightTree) {
    override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider =
        EnvironmentBasedStandardLibrariesPathProvider

    override fun configure(builder: TestConfigurationBuilder) = with(builder) {
        super.configure(builder)
        defaultDirectives {
            RUN_PIPELINE_TILL with TestPhase.BACKEND
            +CodegenTestDirectives.IGNORE_DEXING
            +JvmEnvironmentConfigurationDirectives.FULL_JDK
        }
        configureKotrail()
    }

    /** Redirects to the active Kotlin version's override of this fixture, when there is one. */
    override fun runTest(filePath: String) {
        super.runTest(TestDataOverlay.resolve(filePath))
    }
}

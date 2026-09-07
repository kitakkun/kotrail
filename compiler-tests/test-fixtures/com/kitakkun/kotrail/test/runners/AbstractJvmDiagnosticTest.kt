package com.kitakkun.kotrail.test.runners

import com.kitakkun.kotrail.test.services.TestDataOverlay
import com.kitakkun.kotrail.test.services.configureKotrail
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.directives.TestPhaseDirectives.RUN_PIPELINE_TILL
import org.jetbrains.kotlin.test.runners.AbstractFirPhasedDiagnosticTest
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.TestPhase

/** FIR diagnostic tests: `<!DIAGNOSTIC!>...<!>` markers in `testData/diagnostics`. */
open class AbstractJvmDiagnosticTest : AbstractFirPhasedDiagnosticTest(FirParser.LightTree) {
    override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider =
        EnvironmentBasedStandardLibrariesPathProvider

    override fun configure(builder: TestConfigurationBuilder) = with(builder) {
        super.configure(builder)
        defaultDirectives {
            RUN_PIPELINE_TILL with TestPhase.FRONTEND
            +JvmEnvironmentConfigurationDirectives.FULL_JDK
        }
        configureKotrail()
    }

    /** Redirects to the active Kotlin version's override of this fixture, when there is one. */
    override fun runTest(filePath: String) {
        super.runTest(TestDataOverlay.resolve(filePath))
    }
}

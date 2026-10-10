package com.kitakkun.kotrail.test.compat

import org.jetbrains.kotlin.test.model.AfterAnalysisChecker
import org.jetbrains.kotlin.test.services.TestServices

/**
 * The framework's after-analysis checker, told whether an earlier step failed.
 *
 * Since 2.4.20: `AfterAnalysisChecker.check` takes `thereWereFailures: Boolean`; before, it took
 * the list of failed assertions.
 */
abstract class AfterAnalysisCheckerBase(testServices: TestServices) : AfterAnalysisChecker(testServices) {
    abstract fun checkAfterAnalysis(thereWereFailures: Boolean)

    override fun check(thereWereFailures: Boolean) = checkAfterAnalysis(thereWereFailures)
}

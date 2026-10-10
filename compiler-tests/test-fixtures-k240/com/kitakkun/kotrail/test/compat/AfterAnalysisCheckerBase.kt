package com.kitakkun.kotrail.test.compat

import org.jetbrains.kotlin.test.WrappedException
import org.jetbrains.kotlin.test.model.AfterAnalysisChecker
import org.jetbrains.kotlin.test.services.TestServices

/**
 * The framework's after-analysis checker, told whether an earlier step failed.
 *
 * Before 2.4.20: `AfterAnalysisChecker.check` takes the list of failed assertions; from 2.4.20 on,
 * `thereWereFailures: Boolean`.
 */
abstract class AfterAnalysisCheckerBase(testServices: TestServices) : AfterAnalysisChecker(testServices) {
    abstract fun checkAfterAnalysis(thereWereFailures: Boolean)

    override fun check(failedAssertions: List<WrappedException>) = checkAfterAnalysis(failedAssertions.isNotEmpty())
}

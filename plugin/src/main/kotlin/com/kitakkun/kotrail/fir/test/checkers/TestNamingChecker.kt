package com.kitakkun.kotrail.fir.test.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.TestNamingStyle
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.test.isTestFunction
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isOverride

/**
 * Requires test functions to be named the way the project decided:
 *
 * ```kotlin
 * @Test fun `returns an empty list when nothing matches`() { }   // backticked (the default)
 * @Test fun returnsEmptyList() { }                               // reported
 * ```
 *
 * Kotlin's coding conventions allow method names with spaces in tests, and a sentence says what
 * a test verifies where an identifier only hints at it. The rule counts words rather than looking
 * at the source: a name with a space can only have been written in backticks, so requiring at
 * least `test.naming.minWords` words asks for a backticked sentence and rejects both
 * `returnsEmptyList` and a backticked single word.
 *
 * Set `test.naming.style=identifier` for compilations whose targets reject method names with
 * spaces — Android instrumented tests run on a device and fail at runtime with such names — and
 * the rule asks for the opposite. Which annotations mark a test is up to `test.annotations`.
 *
 * Overrides and `expect` declarations are left alone, because their names are fixed elsewhere.
 */
object TestNamingChecker : FirSimpleFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.TEST_NAMING)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.isOverride || declaration.isExpect) return
        if (declaration.name.isSpecial) return
        if (!declaration.symbol.isTestFunction(context.session, config.test.annotations)) return

        val name = declaration.name.asString()
        val severity = config.severity(KotrailRule.TEST_NAMING)
        when (config.test.namingStyle) {
            TestNamingStyle.BACKTICKED -> {
                val words = name.split(' ').count { it.isNotBlank() }
                if (words >= config.test.minNameWords) return
                reporter.reportOn(
                    source,
                    KotrailDiagnostics.TEST_NAME_NOT_DESCRIPTIVE.at(severity),
                    name,
                    config.test.minNameWords.toString(),
                )
            }
            TestNamingStyle.IDENTIFIER -> {
                if (name.all { it.isLetterOrDigit() || it == '_' }) return
                reporter.reportOn(source, KotrailDiagnostics.TEST_NAME_NOT_IDENTIFIER.at(severity), name)
            }
        }
    }
}

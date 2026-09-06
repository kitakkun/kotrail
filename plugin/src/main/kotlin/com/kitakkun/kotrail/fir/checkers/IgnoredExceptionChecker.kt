package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirTryExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirCatch
import org.jetbrains.kotlin.fir.expressions.FirThrowExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.SpecialNames

/**
 * Reports a catch clause that never looks at the exception it caught:
 *
 * ```kotlin
 * try { parse(input) } catch (e: NumberFormatException) { }          // silently dropped
 * try { parse(input) } catch (e: NumberFormatException) { return 0 } // `e` unused
 * ```
 *
 * A caught exception that is neither handled, logged, wrapped, nor rethrown hides failures.
 * The rule stays quiet when the parameter is named `_` or starts with `ignored` (the swallow
 * is declared on purpose), and when the body contains a `throw` (the exception is being
 * translated into another one). Each catch clause is judged on its own, so a try with several
 * clauses can report more than once.
 */
object IgnoredExceptionChecker : FirTryExpressionChecker(MppCheckerKind.Common) {
    private const val IGNORED_PREFIX = "ignored"

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirTryExpression) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.NO_IGNORED_EXCEPTION)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val severity = context.session.kotrailConfig.severity(KotrailRule.NO_IGNORED_EXCEPTION)

        for (catch in expression.catches) {
            val catchSource = catch.source ?: continue
            if (catchSource.kind is KtFakeSourceElementKind) continue
            if (catch.isDeliberatelyIgnored()) continue
            val usage = UsageVisitor(catch.parameter.symbol)
            catch.block.accept(usage)
            if (usage.referencesParameter || usage.throws) continue
            reporter.reportOn(catchSource, KotrailDiagnostics.IGNORED_EXCEPTION.at(severity), catch.parameter.name.asString())
        }
    }

    private fun FirCatch.isDeliberatelyIgnored(): Boolean {
        val name = parameter.name
        if (name == SpecialNames.UNDERSCORE_FOR_UNUSED_VAR) return true
        val text = name.asString()
        return text == "_" || text.startsWith(IGNORED_PREFIX)
    }

    /** Looks for any resolved reference to the catch parameter and for any `throw` in the clause body. */
    private class UsageVisitor(private val parameter: FirBasedSymbol<*>) : FirVisitorVoid() {
        var referencesParameter = false
        var throws = false

        override fun visitElement(element: FirElement) {
            if (referencesParameter || throws) return
            element.acceptChildren(this)
        }

        override fun visitResolvedNamedReference(resolvedNamedReference: FirResolvedNamedReference) {
            if (resolvedNamedReference.resolvedSymbol == parameter) referencesParameter = true
        }

        override fun visitThrowExpression(throwExpression: FirThrowExpression) {
            throws = true
        }
    }
}

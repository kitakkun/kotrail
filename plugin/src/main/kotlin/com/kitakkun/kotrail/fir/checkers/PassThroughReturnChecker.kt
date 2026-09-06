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
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.declarations.utils.isOperator
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.references.toResolvedValueParameterSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Reports a function that hands one of its inputs straight back on every return path:
 *
 * ```kotlin
 * fun cache(user: User): User { store(user); return user }   // caller already has `user`
 * fun String.logged(): String { log(this); return this }
 * ```
 *
 * The return value carries no information; the function should return `Unit` (or compute
 * something). Functions that pick between inputs, return a different value on some path, are
 * overrides, operators, or inline helpers are left alone.
 */
object PassThroughReturnChecker : FirSimpleFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.NO_PASS_THROUGH_RETURN)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.isOverride || declaration.isExpect || declaration.isOperator || declaration.isInline) return
        val body = declaration.body ?: return

        val collector = ReturnCollector(declaration)
        body.accept(collector)
        if (collector.returns.isEmpty()) return

        val parameters = declaration.valueParameters.associate { it.symbol to it.name.asString() }
        val receiver = declaration.receiverParameter?.symbol
        val returned: Set<String?> = collector.returns.map { expression ->
            when (val e = expression.unwrapSmartCast()) {
                is FirPropertyAccessExpression ->
                    e.calleeReference.toResolvedValueParameterSymbol()?.let { parameters[it] }
                is FirThisReceiverExpression ->
                    if (receiver != null && e.calleeReference.boundSymbol == receiver) "this" else null
                else -> null
            }
        }.toSet()

        val single = returned.singleOrNull() ?: return
        val severity = context.session.kotrailConfig.severity(KotrailRule.NO_PASS_THROUGH_RETURN)
        reporter.reportOn(source, KotrailDiagnostics.PASS_THROUGH_RETURN.at(severity), declaration.name.asString(), single)
    }

    private fun FirExpression.unwrapSmartCast(): FirExpression =
        if (this is FirSmartCastExpression) originalExpression.unwrapSmartCast() else this

    /** Collects the result expressions of every `return` that targets [function], skipping nested lambdas' returns. */
    private class ReturnCollector(private val function: FirNamedFunction) : FirVisitorVoid() {
        val returns = mutableListOf<FirExpression>()

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitReturnExpression(returnExpression: FirReturnExpression) {
            if (returnExpression.target.labeledElement === function) {
                returns += returnExpression.result
            }
            returnExpression.acceptChildren(this)
        }
    }
}

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirElvisExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirSafeCallExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirThrowExpression

/**
 * Caps how many times one expression falls back with `?:`:
 *
 * ```kotlin
 * user?.displayName ?: user?.email ?: cached?.name ?: "unknown"   // reported at the default limit of 2
 * user?.displayName ?: "unknown"                                    // fine
 * user?.displayName ?: cached?.name ?: return                      // fine: the escape is not a candidate
 * ```
 *
 * Each `?:` is another case the reader evaluates in order to learn which value wins. A trailing
 * `?: return` or `?: throw` is an exit, not a candidate, and is not counted. Only the outermost
 * expression of a chain is reported, once, whichever way the chain is associated; a chain inside
 * a lambda or a parenthesized argument is its own expression.
 */
object ElvisChainChecker : FirElvisExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirElvisExpression) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.NULL_CHAIN_LENGTH)) return
        val limit = config.nullChain.maxElvis
        if (limit <= 0) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (context.parentOf(expression) is FirElvisExpression) return

        val fallbacks = expression.countFallbacks()
        if (fallbacks <= limit) return
        reportKotrail(source, KotrailDiagnostics.ELVIS_CHAIN_TOO_LONG, "$fallbacks times (limit $limit)")
    }

    /** The `?:` operators of the connected chain, minus the one whose right side leaves the function. */
    private fun FirElvisExpression.countFallbacks(): Int {
        val rhs = rhs.unwrapSmartCast()
        val own = if (rhs is FirReturnExpression || rhs is FirThrowExpression) 0 else 1
        return own + lhs.unwrapSmartCast().nestedFallbacks() + rhs.nestedFallbacks()
    }

    private fun FirExpression.nestedFallbacks(): Int = if (this is FirElvisExpression) countFallbacks() else 0
}

/**
 * Caps how deeply one expression chains `?.`:
 *
 * ```kotlin
 * order?.customer?.address?.city?.name   // reported when maxSafeCalls is 3
 * ```
 *
 * Every `?.` is a point that may be null, and the caller of the whole expression cannot tell
 * which one was. Off unless `nullChainLength.maxSafeCalls` is set: deep chains are common over
 * external data models, and a project decides where its line is.
 */
object SafeCallChainChecker : FirSafeCallExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirSafeCallExpression) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.NULL_CHAIN_LENGTH)) return
        val limit = config.nullChain.maxSafeCalls
        if (limit <= 0) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val parent = context.parentOf(expression)
        if (parent is FirSafeCallExpression && parent.receiver.unwrapSmartCast() === expression) return

        val depth = expression.depth()
        if (depth <= limit) return
        reportKotrail(source, KotrailDiagnostics.SAFE_CALL_CHAIN_TOO_LONG, "$depth times (limit $limit)")
    }

    /** The `?.` operators along the receiver chain; a safe call inside an argument is a chain of its own. */
    private fun FirSafeCallExpression.depth(): Int {
        val receiver = receiver.unwrapSmartCast()
        return 1 + if (receiver is FirSafeCallExpression) receiver.depth() else 0
    }
}

/** The element the checker context puts around [expression], looking through smart casts. */
private fun CheckerContext.parentOf(expression: FirExpression): FirElement? {
    val elements = containingElements
    var index = elements.lastIndex
    while (index >= 0 && (elements[index] === expression || elements[index] is FirSmartCastExpression)) index--
    return elements.getOrNull(index)
}

private fun FirExpression.unwrapSmartCast(): FirExpression =
    if (this is FirSmartCastExpression) originalExpression.unwrapSmartCast() else this

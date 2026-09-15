package com.kitakkun.kotrail.fir.compose.insets

import com.kitakkun.kotrail.compose.insets.InsetsSet
import com.kitakkun.kotrail.compose.insets.Sides
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol

/**
 * Statically evaluates `WindowInsets` and `WindowInsetsSides` expressions built from the
 * foundation-layout API, plus the Material 3 `*Defaults.windowInsets` properties. Returns
 * `null` for anything it cannot prove.
 */
object WindowInsetsExpressionEvaluator {
    fun evaluateInsets(expression: FirExpression?): InsetsSet? {
        return when (val expr = expression?.unwrapArgument()?.unwrapSmartCast()) {
            null -> null
            is FirPropertyAccessExpression -> {
                val callableId = expr.calleeReference.toResolvedCallableSymbol()?.callableId ?: return null
                WindowInsetsNames.KNOWN_INSETS_PROPERTIES[callableId]?.let { return it }
                if (callableId.packageName != WindowInsetsNames.FOUNDATION_LAYOUT) return null
                InsetsSet.fromTypeName(callableId.callableName.asString())
            }
            is FirFunctionCall -> {
                val callableId = expr.calleeReference.toResolvedCallableSymbol()?.callableId ?: return null
                // Fixed insets built from numbers handle no system inset, whatever the numbers.
                if (callableId == WindowInsetsNames.WINDOW_INSETS_FACTORY && expr.explicitReceiver == null) return InsetsSet.EMPTY
                if (callableId.packageName != WindowInsetsNames.FOUNDATION_LAYOUT) return null
                val receiver = evaluateInsets(expr.explicitReceiver) ?: return null
                val argument = expr.arguments.firstOrNull()
                when (callableId.callableName) {
                    WindowInsetsNames.ONLY -> evaluateSides(argument)?.let(receiver::only)
                    WindowInsetsNames.UNION, WindowInsetsNames.ADD -> evaluateInsets(argument)?.let(receiver::union)
                    WindowInsetsNames.EXCLUDE -> evaluateInsets(argument)?.let(receiver::minus)
                    else -> null
                }
            }
            else -> null
        }
    }

    fun evaluateSides(expression: FirExpression?): Int? {
        return when (val expr = expression?.unwrapArgument()?.unwrapSmartCast()) {
            null -> null
            is FirPropertyAccessExpression -> {
                val callableId = expr.calleeReference.toResolvedCallableSymbol()?.callableId ?: return null
                if (callableId.classId != WindowInsetsNames.WINDOW_INSETS_SIDES_COMPANION) return null
                Sides.fromName(callableId.callableName.asString())
            }
            is FirFunctionCall -> {
                val callableId = expr.calleeReference.toResolvedCallableSymbol()?.callableId ?: return null
                if (callableId.classId != WindowInsetsNames.WINDOW_INSETS_SIDES) return null
                if (callableId.callableName != WindowInsetsNames.PLUS) return null
                val left = evaluateSides(expr.explicitReceiver) ?: return null
                val right = evaluateSides(expr.arguments.firstOrNull()) ?: return null
                left or right
            }
            else -> null
        }
    }

    private fun FirExpression.unwrapSmartCast(): FirExpression =
        if (this is FirSmartCastExpression) originalExpression.unwrapSmartCast() else this
}

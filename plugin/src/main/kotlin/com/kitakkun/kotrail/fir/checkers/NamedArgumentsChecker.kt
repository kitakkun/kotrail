package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.isJavaOrEnhancement
import org.jetbrains.kotlin.fir.declarations.utils.isInfix
import org.jetbrains.kotlin.fir.declarations.utils.isOperator
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirImplicitInvokeCall
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.renderReadable

/**
 * Asks for named arguments when a call passes several positional arguments to parameters of
 * one declared type, because such arguments can be swapped without the compiler noticing:
 *
 * ```kotlin
 * fun move(x: Int, y: Int, z: Int)
 * move(1, 2, 3)                  // reported: 3 positional Int arguments
 * move(x = 1, y = 2, z = 3)      // fine
 * move(1, 2, z = 3)              // fine: only 2 positional Int arguments
 * ```
 *
 * The threshold is `namedArguments.minSameTypeArguments`. Types are compared as declared on the
 * callee's parameters, exactly (`Int` and `Int?` are different groups). Lambda arguments never
 * count. The rule stays quiet where naming is impossible or pointless: Java callees, operator
 * and infix calls, `invoke` calls, calls with a vararg parameter, and callees with fewer
 * parameters than the threshold.
 */
object NamedArgumentsChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.NAMED_ARGUMENTS_FOR_REPEATED_TYPES)) return
        val threshold = config.namedArguments.minSameTypeArguments
        if (threshold <= 1) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (expression is FirImplicitInvokeCall) return

        val callee = expression.calleeReference.toResolvedFunctionSymbol(discardErrorReference = true) ?: return
        if (callee.isJavaOrEnhancement) return
        if (callee.isOperator || callee.isInfix) return
        if (callee.valueParameterSymbols.size < threshold) return
        if (callee.valueParameterSymbols.any { it.isVararg }) return

        val positional = positionalArguments(expression) ?: return
        val largestGroup = positional
            .groupBy { it.returnTypeRef.coneType }
            .entries
            .filter { (_, parameters) -> parameters.size >= threshold }
            .maxByOrNull { (_, parameters) -> parameters.size }
            ?: return

        val description = "${largestGroup.value.size} positional ${largestGroup.key.describe()} arguments"
        val severity = config.severity(KotrailRule.NAMED_ARGUMENTS_FOR_REPEATED_TYPES)
        reporter.reportOn(source, KotrailDiagnostics.NAMED_ARGUMENTS_REQUIRED.at(severity), description)
    }

    /**
     * Parameters that receive a positional, non-lambda argument, or null when the argument list
     * cannot be matched against what the user wrote.
     *
     * The resolved argument list drops the named-argument wrappers, so the original list is
     * consulted to tell named from positional arguments. Both lists follow source order, and
     * without a vararg parameter they have the same length, so they are matched by position.
     */
    private fun positionalArguments(call: FirFunctionCall): List<FirValueParameter>? {
        val argumentList = call.argumentList as? FirResolvedArgumentList ?: return null
        val original = argumentList.originalArgumentList?.arguments ?: return null
        val mapping = argumentList.mapping
        if (original.size != mapping.size) return null

        return original.zip(mapping.values)
            .filter { (argument, _) -> argument !is FirNamedArgumentExpression && !argument.isLambda() }
            .map { (_, parameter) -> parameter }
    }

    private fun FirExpression.isLambda(): Boolean = unwrapArgument() is FirAnonymousFunctionExpression

    private fun ConeKotlinType.describe(): String = renderReadable()
}

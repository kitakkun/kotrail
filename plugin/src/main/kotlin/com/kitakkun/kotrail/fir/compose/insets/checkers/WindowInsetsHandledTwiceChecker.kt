package com.kitakkun.kotrail.fir.compose.insets.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.insets.WindowInsetsNames
import com.kitakkun.kotrail.fir.compose.insets.windowInsetsHandlingService
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType

/**
 * Warns when a `Modifier` argument applies inset padding that the called composable already
 * handles internally, which produces doubled padding at runtime.
 */
object WindowInsetsHandledTwiceChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_WINDOW_INSETS) || !config.isEnabled(KotrailRule.COMPOSE_WINDOW_INSETS_HANDLED_TWICE)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        val service = context.session.windowInsetsHandlingService
        if (!service.isComposable(callee)) return

        val mapping = expression.resolvedArgumentMapping ?: return
        val modifierArguments = mapping.entries
            .filter { (_, parameter) -> parameter.returnTypeRef.coneType.classId == WindowInsetsNames.MODIFIER }
            .map { it.key }
        if (modifierArguments.isEmpty()) return

        val calleeHandles = service.handledInsets(callee).handled
        if (calleeHandles.isEmpty) return

        for (argument in modifierArguments) {
            val applied = service.handledByExpression(argument).handled
            val overlap = applied.intersect(calleeHandles)
            if (overlap.isEmpty) continue
            reporter.reportOn(
                source,
                KotrailDiagnostics.WINDOW_INSETS_HANDLED_TWICE.at(context.session.kotrailConfig.severity(KotrailRule.COMPOSE_WINDOW_INSETS_HANDLED_TWICE)),
                callee.name.asString(),
                overlap.describe(),
            )
        }
    }
}

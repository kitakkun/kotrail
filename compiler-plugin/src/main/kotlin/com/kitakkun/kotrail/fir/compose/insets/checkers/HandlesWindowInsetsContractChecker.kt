package com.kitakkun.kotrail.fir.compose.insets.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.insets.windowInsetsHandlingService
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction

/**
 * Verifies `@HandlesWindowInsets` contracts: every declared inset and side must be handled by the
 * function body, directly or through the composables it calls.
 *
 * For composables without a contract this checker still runs the analysis so that the result is
 * cached while FIR bodies are available; the IR metadata writer reads that cache after Fir2Ir,
 * when bodies have already been released.
 */
object HandlesWindowInsetsContractChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.COMPOSE_WINDOW_INSETS)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val service = context.session.windowInsetsHandlingService
        val contract = service.declaredContract(declaration.symbol)
        if (contract == null) {
            if (service.isComposable(declaration.symbol)) service.handledInsets(declaration.symbol)
            return
        }

        // handledInsets() short-circuits to the declared contract, so analyze the body directly.
        val body = declaration.body
        val analysis = if (body == null) null else service.handledByExpression(body)
        val handled = analysis?.handled
        val missing = if (handled == null) contract else contract.minus(handled)
        if (missing.isEmpty) return

        val config = context.session.kotrailConfig
        if (analysis?.unverifiable == true) {
            reportKotrail(
                source,
                KotrailDiagnostics.WINDOW_INSETS_HANDLING_UNVERIFIABLE,
                missing.describe(),
            )
        } else {
            reportKotrail(source, KotrailDiagnostics.WINDOW_INSETS_NOT_HANDLED, missing.describe())
        }
    }
}

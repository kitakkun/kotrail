package com.kitakkun.kotrail.fir.preconditions.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.preconditions.preconditionService
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass

/**
 * Computes and caches the preconditions of every source declaration while FIR bodies are still
 * available, so that the IR metadata writer can read them after Fir2Ir has released the bodies.
 * Reports nothing.
 */
object PreconditionWarmup {
    object FunctionChecker : FirSimpleFunctionChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirNamedFunction) {
            if (!context.session.kotrailConfig.isEnabled(KotrailRule.PRECONDITIONS)) return
            context.session.preconditionService.preconditionsOf(declaration.symbol)
        }
    }

    object ClassChecker : FirRegularClassChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirRegularClass) {
            if (!context.session.kotrailConfig.isEnabled(KotrailRule.PRECONDITIONS)) return
            context.session.preconditionService.preconditionsOfClass(declaration.symbol)
        }
    }
}

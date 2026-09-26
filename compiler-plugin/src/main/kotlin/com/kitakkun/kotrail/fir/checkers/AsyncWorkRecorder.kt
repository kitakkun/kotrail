package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.concurrency.asyncWorkService
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend

/**
 * Not a rule: runs the async-work analysis on every non-suspending function of the compilation
 * while the checkers run, so that [com.kitakkun.kotrail.ir.concurrency.InferredStartsAsyncWorkMetadataWriter]
 * finds the answers computed when it writes the metadata for other modules. The
 * delayForCompletion rule reads the same answers for the callees it meets.
 */
object AsyncWorkRecorder : NamedFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.DELAY_FOR_COMPLETION)) return
        if (declaration.isSuspend || declaration.body == null) return
        context.session.asyncWorkService.startsAsyncWork(declaration.symbol)
    }
}

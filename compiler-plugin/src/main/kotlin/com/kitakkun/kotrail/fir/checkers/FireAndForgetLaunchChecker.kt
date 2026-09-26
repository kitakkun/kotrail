package com.kitakkun.kotrail.fir.checkers

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction

/** Stub: to be implemented. */
object FireAndForgetLaunchChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
    }
}

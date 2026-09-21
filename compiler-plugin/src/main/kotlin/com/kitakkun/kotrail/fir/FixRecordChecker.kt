package com.kitakkun.kotrail.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.declarations.FirFile

/**
 * Starts the fix record of every file the compilation visits, before any rule reports on it. The
 * file checkers run in registration order and before the file's declarations are checked, so
 * this one is registered first: a record is always fresh when the first fix of the file lands.
 */
object FixRecordChecker : FirFileChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val directory = context.session.kotrailConfig.fixesDir ?: return
        val path = declaration.sourceFile?.path ?: return
        FixRecords.begin(directory, path)
    }
}

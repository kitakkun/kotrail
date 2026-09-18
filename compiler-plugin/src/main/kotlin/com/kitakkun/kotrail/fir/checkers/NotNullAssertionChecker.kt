package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirCheckNotNullCallChecker
import org.jetbrains.kotlin.fir.expressions.FirCheckNotNullCall

/**
 * Rejects the not-null assertion operator:
 *
 * ```kotlin
 * val name = user!!.name        // -> user?.name, user ?: return, requireNotNull(user)
 * val first = list.firstOrNull()!!
 * ```
 *
 * `!!` turns a nullable value into a `NullPointerException` at the exact place where the code
 * could have said what to do instead. Every `!!` written in source is reported on the whole
 * `x!!` expression; compiler-generated null checks (fake sources) are ignored. There are no
 * exemptions: suppress a deliberate use with `@Suppress("NOT_NULL_ASSERTION")`.
 */
object NotNullAssertionChecker : FirCheckNotNullCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirCheckNotNullCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.NO_NOT_NULL_ASSERTION)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        reportKotrail(source, KotrailDiagnostics.NOT_NULL_ASSERTION)
    }
}

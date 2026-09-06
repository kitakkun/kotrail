package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression

/**
 * Reports a named function whose block body does nothing but `return` one expression:
 *
 * ```kotlin
 * fun total(items: List<Item>): Int {
 *     return items.sumOf { it.price }      // -> fun total(items: List<Item>): Int = items.sumOf { it.price }
 * }
 * ```
 *
 * Such a body is an expression body with extra ceremony; `= expr` says the same thing and keeps
 * the (optional) explicit return type. FIR desugars a real expression body into a block holding
 * one return whose source is the fake kind [KtFakeSourceElementKind.ImplicitReturn.FromExpressionBody],
 * so the rule only fires when the return statement has a real source, which means the user wrote
 * `return` inside braces. Bodies with more than one statement, a bare `return` in a
 * `Unit` function (its result is the synthesized `Unit` with a fake source), and returns nested
 * in inner constructs are left alone. Local functions and overrides are reported like any other.
 */
object PreferExpressionBodyChecker : FirSimpleFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.PREFER_EXPRESSION_BODY)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val body = declaration.body ?: return
        // An expression body is a synthesized single-expression block; only real braces qualify.
        if (body.source?.kind !is KtRealSourceElementKind) return
        val statement = body.statements.singleOrNull() as? FirReturnExpression ?: return
        // A return synthesized from an expression body carries a fake source; a written `return` is real.
        if (statement.source?.kind !is KtRealSourceElementKind) return
        if (statement.target.labeledElement !== declaration) return
        // `return` without a value: the result is a synthesized `Unit`, and `= Unit` would not read better.
        if (statement.result.source?.kind is KtFakeSourceElementKind.ImplicitUnit) return

        val severity = context.session.kotrailConfig.severity(KotrailRule.PREFER_EXPRESSION_BODY)
        reporter.reportOn(source, KotrailDiagnostics.PREFER_EXPRESSION_BODY.at(severity))
    }
}

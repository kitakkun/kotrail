package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.fix.FixBuilder
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirWhenExpressionChecker
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.expressions.ExhaustivenessStatus
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isBooleanOrNullableBoolean

/**
 * Reports an `else` branch on a `when` whose other branches already cover every case of a
 * sealed, enum, or Boolean subject:
 *
 * ```kotlin
 * when (state) {
 *     is Loading -> ...
 *     is Loaded -> ...
 *     else -> ...          // unreachable today; hides a missing branch tomorrow
 * }
 * ```
 *
 * The `else` can never run, and once a new subclass or enum entry is added the compiler no
 * longer points at this `when`: the new case silently falls into `else`. Removing the branch
 * keeps the `when` exhaustive and lets the compiler flag every addition.
 *
 * Exhaustiveness is taken from the compiler's own computation
 * ([ExhaustivenessStatus.RedundantlyExhaustive]), so sealed hierarchies, enum entries,
 * booleans, and nullable subjects with a `null ->` branch are all handled the way the compiler
 * handles them. The rule stays quiet for `when` without a subject, subjects of other types
 * (where the compiler only recognizes `is <SelfType>` coverage), non-exhaustive branch sets,
 * and a `when` whose only branch is `else`.
 */
object RedundantElseChecker : FirWhenExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirWhenExpression) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.NO_REDUNDANT_ELSE)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (expression.exhaustivenessStatus != ExhaustivenessStatus.RedundantlyExhaustive) return

        val subjectType = expression.subjectVariable?.returnTypeRef?.coneType ?: return
        if (!subjectType.isSealedEnumOrBoolean(context.session)) return

        for (branch in expression.branches) {
            if (branch.condition !is FirElseIfTrueCondition) continue
            val branchSource = branch.source ?: continue
            // The fix drops the branch; the formatter takes care of the blank line it leaves.
            val fix = listOf(FixBuilder.delete(branchSource))
            reportKotrail(branchSource, KotrailDiagnostics.REDUNDANT_ELSE_IN_EXHAUSTIVE_WHEN, fix)
        }
    }

    /** `true` for a (possibly nullable) sealed class or interface, enum class, or `Boolean`. */
    private fun ConeKotlinType.isSealedEnumOrBoolean(session: FirSession): Boolean {
        val expanded = fullyExpandedType(session)
        if (expanded.isBooleanOrNullableBoolean) return true
        val classSymbol = expanded.toRegularClassSymbol(session) ?: return false
        return classSymbol.modality == Modality.SEALED || classSymbol.classKind == ClassKind.ENUM_CLASS
    }
}

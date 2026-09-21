package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.WhenBranchStyle
import com.kitakkun.kotrail.compat.qualifierClassId
import com.kitakkun.kotrail.fir.KotrailDiagnostics
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
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.ClassId

/**
 * Makes the branches of a `when` over a sealed type read the same way whether the case is a
 * class or an object:
 *
 * ```kotlin
 * when (action) {
 *     is Save -> ...
 *     Cancel -> ...        // reported (style: is): write `is Cancel ->`
 * }
 * ```
 *
 * An object case can be written either way; `Cancel ->` compares with `equals`, `is Cancel ->`
 * checks the type, and both select the same branch and count toward exhaustiveness. Mixing them
 * makes the reader ask why. `sealedWhenBranchStyle.style` picks the form: `is` (default) or
 * `object`. Only a `when` with a subject of a sealed type is inspected; `else`, guards, and
 * conditions that are neither an `is` check nor an object comparison are left alone.
 */
object SealedWhenBranchStyleChecker : FirWhenExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirWhenExpression) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.SEALED_WHEN_BRANCH_STYLE)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val session = context.session
        val subjectType = expression.subjectVariable?.returnTypeRef?.coneType ?: return
        val subject = subjectType.fullyExpandedType().toRegularClassSymbol() ?: return
        if (subject.modality != Modality.SEALED) return

        for (branch in expression.branches) {
            for (condition in branch.condition.alternatives()) {
                when (config.sealedWhen.style) {
                    WhenBranchStyle.IS -> condition.objectCompared(session)?.let { objectClass ->
                        val name = objectClass.name.asString()
                        reportKotrail(condition.source ?: continue, KotrailDiagnostics.SEALED_WHEN_BRANCH_STYLE, name, "is $name")
                    }
                    WhenBranchStyle.OBJECT -> condition.objectChecked(session)?.let { objectClass ->
                        val name = objectClass.name.asString()
                        reportKotrail(condition.source ?: continue, KotrailDiagnostics.SEALED_WHEN_BRANCH_STYLE, "is $name", name)
                    }
                }
            }
        }
    }

    /** `A, B ->` is one condition joined with `||`; each alternative is judged on its own. */
    private fun FirExpression.alternatives(): List<FirExpression> =
        if (this is FirBooleanOperatorExpression) leftOperand.alternatives() + rightOperand.alternatives() else listOf(this)

    /** The object an `Obj ->` condition compares the subject with, or `null`. */
    private fun FirExpression.objectCompared(session: FirSession): FirRegularClassSymbol? {
        if (this !is FirEqualityOperatorCall || operation != FirOperation.EQ) return null
        val qualifier = arguments.filterIsInstance<FirResolvedQualifier>().singleOrNull() ?: return null
        return qualifier.qualifierClassId?.toObjectSymbol(session)
    }

    /** The object an `is Obj ->` condition checks the subject against, or `null`. */
    private fun FirExpression.objectChecked(session: FirSession): FirRegularClassSymbol? {
        if (this !is FirTypeOperatorCall || operation != FirOperation.IS) return null
        val classSymbol = conversionTypeRef.coneType.fullyExpandedType(session).toRegularClassSymbol(session) ?: return null
        return classSymbol.takeIf { it.classKind == ClassKind.OBJECT }
    }

    private fun ClassId.toObjectSymbol(session: FirSession): FirRegularClassSymbol? =
        (session.symbolProvider.getClassLikeSymbolByClassId(this) as? FirRegularClassSymbol)?.takeIf { it.classKind == ClassKind.OBJECT }
}

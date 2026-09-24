package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirBasicDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.utils.isActual
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSomeFunctionType

/**
 * Puts the data a function works on before the functions it applies to it:
 *
 * ```kotlin
 * fun SnackbarAction(onClick: () -> Unit, label: String)          // reported: onClick before label
 * class Poller(val onTick: (Long) -> Unit, val intervalMs: Long)  // reported
 * fun load(rows: List<Row>, limit: Int, transform: (Row) -> Item) // fine
 * ```
 *
 * A signature reads as "what it works on, then what it does with it"; a callback ahead of the
 * data it is called with reads backwards, and a function-typed parameter that is not last cannot
 * take a trailing lambda, so every call has to name it and bury the lambda inside the argument
 * list. The rule asks that every function-typed parameter without a default come after every
 * parameter that is neither function-typed nor defaulted; the relative order inside each group
 * is the author's, and parameters with defaults are left where they are, since an optional
 * callback belongs with the other optional parameters (for a composable, `noTrailingCallback`
 * says where those go). Overrides and `actual` declarations follow their supertype or `expect`
 * and are left alone, as are lambdas.
 */
object ParameterOrderChecker : FirBasicDeclarationChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirDeclaration) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.PARAMETER_ORDER)) return
        val function = declaration as? FirFunction ?: return
        if (function !is FirNamedFunction && function !is FirConstructor) return
        val source = function.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (function is FirNamedFunction && (function.isOverride || function.isActual)) return
        if (function is FirConstructor && function.isActual) return

        val parameters = function.valueParameters
        if (parameters.size < 2) return
        val session = context.session
        for ((index, parameter) in parameters.withIndex()) {
            if (parameter.defaultValue != null || !parameter.isFunctionTyped(session)) continue
            val data = parameters.drop(index + 1).firstOrNull { it.defaultValue == null && !it.isFunctionTyped(session) } ?: continue
            val target = parameter.source ?: return
            reportKotrail(target, KotrailDiagnostics.CALLBACK_BEFORE_DATA_PARAMETER, parameter.name.asString(), data.name.asString())
            return
        }
    }

    /**
     * A function type, suspend or not, nullable or not, composable or not, a type alias of one, or
     * a type that carries one in a type argument (`Pair<() -> Unit, String>?`).
     */
    private fun FirValueParameter.isFunctionTyped(session: FirSession): Boolean = returnTypeRef.coneType.carriesFunctionType(session)

    private fun ConeKotlinType.carriesFunctionType(session: FirSession): Boolean {
        val expanded = fullyExpandedType(session)
        if (expanded.isSomeFunctionType(session)) return true
        return expanded.typeArguments.any { argument -> (argument as? ConeKotlinTypeProjection)?.type?.carriesFunctionType(session) == true }
    }
}

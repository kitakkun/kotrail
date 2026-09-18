package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.exclude.CallSite
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.arrayElementType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.resolvedType

/**
 * Rejects calls the project has forbidden. Each `forbiddenCall[<name>]` entry is a predicate
 * over the call, in the same grammar as `exclude` but with atoms about what is called and how:
 *
 * ```properties
 * forbiddenCall.functions=kotlin.io.println, java.lang.Thread.sleep
 * forbiddenCall[globalScope]=fqn(kotlinx.coroutines.launch) && receiver(kotlinx.coroutines.GlobalScope)
 * forbiddenCall[stringLog]=fqn(com.acme.log) && extension(kotlin.String)
 * forbiddenCall[date]=constructor(java.util.Date)
 * ```
 *
 * The plain `forbiddenCall.functions` list is the short form for names alone. Only function and
 * constructor calls are inspected; property accesses and callable references are not, and
 * neither are compiler-generated calls without real source (the `iterator()` of a `for` loop).
 */
object ForbiddenCallChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.FORBIDDEN_CALL)) return
        val entries = config.forbiddenCall.entries
        if (entries.isEmpty()) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return

        val callee = expression.calleeReference.toResolvedFunctionSymbol() ?: return
        val site = expression.callSite(callee, context.session) ?: return
        val match = entries.firstOrNull { it.predicate.matches(site) } ?: return
        reportKotrail(source, KotrailDiagnostics.FORBIDDEN_CALL, site.fqn, match.name)
    }

    /** Describes the call for the predicates; `null` for callees without a stable name, such as local functions. */
    private fun FirFunctionCall.callSite(callee: FirFunctionSymbol<*>, session: FirSession): CallSite? {
        val callableId = callee.callableId ?: return null
        val constructed = (callee as? FirConstructorSymbol)?.callableId?.classId?.asSingleFqName()?.asString()
        val fqn = when (callee) {
            is FirConstructorSymbol -> constructed?.let { "$it.<init>" } ?: return null
            is FirNamedFunctionSymbol -> callableId.asSingleFqName().asString()
            else -> return null
        }
        val receiverExpression = explicitReceiver ?: dispatchReceiver ?: extensionReceiver
        return CallSite(
            fqn = fqn,
            constructedClass = constructed,
            extensionReceiver = callee.resolvedReceiverType?.fqName(session),
            receiver = receiverExpression?.resolvedType?.fqName(session),
            contextParameters = callee.contextParameterSymbols.mapNotNull { it.resolvedReturnType.fqName(session) },
            parameters = callee.valueParameterSymbols.map { parameter ->
                val type = parameter.resolvedReturnType
                (if (parameter.isVararg) type.arrayElementType() ?: type else type).fqName(session) ?: "?"
            },
            annotations = callee.resolvedAnnotationsWithClassIds
                .mapNotNullTo(HashSet()) { it.toAnnotationClassId(session)?.asSingleFqName()?.asString() },
            isSuspend = (callee as? FirNamedFunctionSymbol)?.isSuspend == true,
            isComposable = callee.isComposable(session),
        )
    }

    private fun ConeKotlinType.fqName(session: FirSession): String? =
        fullyExpandedType(session).classId?.asSingleFqName()?.asString()
}

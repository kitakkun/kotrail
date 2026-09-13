package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.isPreview
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.effectiveVisibility
import org.jetbrains.kotlin.fir.declarations.utils.isActual
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isExternal
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.declarations.utils.isOperator
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirSpreadArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * Reports a function that is another function under a different name: its whole body is one call
 * with the same shape as its own signature, receiving its parameters unchanged, in order, and
 * returning what the call returns.
 *
 * ```kotlin
 * fun persist(user: User, force: Boolean): Boolean = store(user, force)   // reported
 * fun String.loud(): String = uppercase()                                  // reported
 * fun saveUser(user: User): Boolean = repository.save(user)               // fine: encapsulates repository
 * fun String.size(): Int = parseImpl(this)                                 // fine: changes the call shape
 * fun connect(host: String) = connect(host, DEFAULT_PORT)                  // fine: supplies a default
 * ```
 *
 * Only the unambiguous shape is reported: a caller could replace the call with the callee's,
 * argument for argument, and nothing would change. Anything that could be a decision is left
 * alone: reaching the callee through a property (that is encapsulation), turning a receiver into
 * an argument or the reverse (a different call shape), generics, a change of `suspend`, a
 * reordering, a default, a conversion, a narrower type, or leaving some of the callee's parameters
 * to their defaults (a narrower API). Overrides, `operator`, `inline`, `actual`, `external` and
 * local functions, `kotlin.jvm`-annotated adapters, factories over a constructor, previews, and a
 * public function over a narrower callee (a facade) are also left alone.
 */
object PassThroughFunctionChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    private val KOTLIN_JVM = FqName("kotlin.jvm")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val session = context.session
        if (!session.kotrailConfig.isEnabled(KotrailRule.NO_PASS_THROUGH_FUNCTION)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.isOverride || declaration.isExpect || declaration.isActual || declaration.isOperator) return
        if (declaration.isInline || declaration.isExternal || declaration.isLocal) return
        if (declaration.typeParameters.isNotEmpty()) return
        if (declaration.annotations.any { it.toAnnotationClassId(session)?.packageFqName == KOTLIN_JVM }) return
        if (declaration.symbol.isPreview(session)) return
        if (declaration.valueParameters.any { it.defaultValue != null }) return

        val call = singleCall(declaration) ?: return
        val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        if (callee == declaration.symbol) return
        if (callee.isOperator || callee.isSuspend != declaration.isSuspend) return
        if (callee.typeParameterSymbols.isNotEmpty()) return
        // A public function over a narrower callee is a facade.
        if (declaration.effectiveVisibility.publicApi && !callee.effectiveVisibility.publicApi) return

        val receiver = declaration.receiverParameter?.symbol
        if (!sameReceiverShape(call, receiver)) return

        val parameters = declaration.valueParameters.map { it.symbol }
        val mapping = call.resolvedArgumentMapping ?: return
        if (mapping.size != parameters.size || mapping.size != callee.valueParameterSymbols.size) return
        for ((index, entry) in mapping.entries.withIndex()) {
            val (argument, calleeParameter) = entry
            val forwarded = forwardedParameter(argument) ?: return
            if (forwarded != parameters[index]) return
            if (!sameType(session, forwarded.resolvedReturnType, calleeParameter.returnTypeRef.coneType)) return
        }
        if (!sameType(session, declaration.returnTypeRef.coneType, call.resolvedType)) return

        val calleeName = callee.callableId.callableName.asString()
        reportKotrail(source, KotrailDiagnostics.PASS_THROUGH_FUNCTION, declaration.name.asString(), calleeName)
    }

    /** The one call the body consists of, whether written as an expression body, a `return`, or a statement. */
    private fun singleCall(declaration: FirNamedFunction): FirFunctionCall? {
        val statement = declaration.body?.statements?.singleOrNull() ?: return null
        val expression = (statement as? FirReturnExpression)?.result ?: statement
        return expression as? FirFunctionCall
    }

    /**
     * The call has the same receiver shape as the function: no explicit receiver, or `this`. An
     * extension's receiver must be the callee's receiver too; a property, a parameter, or a call
     * result as receiver is a choice the function makes.
     */
    private fun sameReceiverShape(call: FirFunctionCall, receiver: FirBasedSymbol<*>?): Boolean {
        val explicit = call.explicitReceiver?.let(::unwrap)
        if (explicit != null && explicit !is FirThisReceiverExpression) return false
        if (receiver == null) return true
        val bound = ((explicit ?: call.extensionReceiver ?: call.dispatchReceiver)?.let(::unwrap) as? FirThisReceiverExpression)
            ?.calleeReference?.boundSymbol
        return bound == receiver
    }

    /** The function's own parameter an argument passes through unchanged, or `null` for anything else. */
    private fun forwardedParameter(argument: FirExpression): FirValueParameterSymbol? {
        var expression = argument.unwrapArgument()
        if (expression is FirVarargArgumentsExpression) {
            val spread = expression.arguments.singleOrNull() as? FirSpreadArgumentExpression ?: return null
            expression = spread.expression
        }
        val access = unwrap(expression) as? FirPropertyAccessExpression ?: return null
        if (access.explicitReceiver != null) return null
        return access.calleeReference.toResolvedCallableSymbol() as? FirValueParameterSymbol
    }

    private fun unwrap(expression: FirExpression): FirExpression =
        if (expression is FirSmartCastExpression) unwrap(expression.originalExpression) else expression

    private fun sameType(session: FirSession, a: ConeKotlinType, b: ConeKotlinType): Boolean =
        AbstractTypeChecker.equalTypes(session.typeContext, a, b)
}

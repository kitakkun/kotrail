package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
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
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * Reports a function whose whole body is one call that receives the function's own parameters
 * unchanged and returns what the call returns:
 *
 * ```kotlin
 * fun saveUser(user: User) = repository.save(user)       // reported: call repository.save directly
 * fun String.loud(): String = uppercase()                 // reported: a rename of uppercase()
 * fun connect(host: String) = connect(host, DEFAULT_PORT) // fine: supplies a default
 * fun items(): List<Item> = mutableItems()                // fine: narrows the type
 * override fun close() = delegate.close()                 // fine: implements the interface
 * ```
 *
 * Such a function is a layer with nothing in it. Every reader has to open it to learn that it
 * does nothing, and an assistant adding "one more layer" is how they accumulate. A wrapper earns
 * its place by doing something: a default value, a conversion, a narrower or wider type, a
 * receiver the callee did not have.
 *
 * Left alone, because forwarding is their purpose: overrides, `operator`, `inline`, `actual`,
 * `external` and local functions; functions carrying a `kotlin.jvm` annotation (Java-facing
 * adapters); factory functions calling a constructor; and a public function over a narrower
 * callee, which is a deliberate facade.
 */
object PassThroughFunctionChecker : FirSimpleFunctionChecker(MppCheckerKind.Common) {
    private val KOTLIN_JVM = FqName("kotlin.jvm")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val session = context.session
        if (!session.kotrailConfig.isEnabled(KotrailRule.NO_PASS_THROUGH_FUNCTION)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.isOverride || declaration.isExpect || declaration.isActual || declaration.isOperator) return
        if (declaration.isInline || declaration.isExternal || declaration.isLocal) return
        if (declaration.annotations.any { it.toAnnotationClassId(session)?.packageFqName == KOTLIN_JVM }) return
        if (declaration.valueParameters.any { it.defaultValue != null }) return

        val call = singleCall(declaration) ?: return
        val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        if (callee == declaration.symbol) return
        // A public function over a narrower callee is a facade, which is a decision.
        if (declaration.effectiveVisibility.publicApi && !callee.effectiveVisibility.publicApi) return

        val parameters = declaration.valueParameters.map { it.symbol }
        val receiver = declaration.receiverParameter?.symbol
        val forwarded = HashSet<FirBasedSymbol<*>>()

        if (!receiverIsForwarded(call, receiver, parameters, forwarded)) return
        val mapping = call.resolvedArgumentMapping ?: return
        for ((argument, parameter) in mapping) {
            val symbol = forwardedSymbol(argument, receiver) ?: return
            if (!forwarded.add(symbol)) return
            if (symbol is FirValueParameterSymbol) {
                if (symbol !in parameters) return
                if (!sameType(session, symbol.resolvedReturnType, parameter.returnTypeRef.coneType)) return
            }
        }
        if (parameters.any { it !in forwarded }) return
        if (receiver != null && receiver !in forwarded) return
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
     * The callee may be reached through nothing, `this`, a property of `this`, or one of the
     * function's own parameters; anything else (a call result, a local) is work of its own.
     */
    private fun receiverIsForwarded(
        call: FirFunctionCall,
        receiver: FirBasedSymbol<*>?,
        parameters: List<FirValueParameterSymbol>,
        forwarded: MutableSet<FirBasedSymbol<*>>,
    ): Boolean {
        val explicit = call.explicitReceiver?.let(::unwrap)
        if (explicit == null) {
            // Implicit receiver: the function's own extension receiver counts as forwarded when the
            // callee is bound to it.
            val implicit = (call.extensionReceiver ?: call.dispatchReceiver)?.let(::unwrap)
            if (implicit is FirThisReceiverExpression && receiver != null && implicit.calleeReference.boundSymbol == receiver) {
                forwarded += receiver
            }
            return true
        }
        return when (explicit) {
            is FirThisReceiverExpression -> {
                if (receiver != null && explicit.calleeReference.boundSymbol == receiver) forwarded += receiver
                true
            }
            is FirPropertyAccessExpression -> when (val symbol = explicit.calleeReference.toResolvedCallableSymbol()) {
                is FirValueParameterSymbol -> symbol in parameters && forwarded.add(symbol)
                is FirPropertySymbol -> explicit.explicitReceiver.let { it == null || unwrap(it) is FirThisReceiverExpression }
                else -> false
            }
            else -> false
        }
    }

    /** The parameter or receiver an argument passes through unchanged, or `null` when it is anything else. */
    private fun forwardedSymbol(argument: FirExpression, receiver: FirBasedSymbol<*>?): FirBasedSymbol<*>? {
        var expression = argument.unwrapArgument()
        if (expression is FirVarargArgumentsExpression) {
            val spread = expression.arguments.singleOrNull() as? FirSpreadArgumentExpression ?: return null
            expression = spread.expression
        }
        return when (val unwrapped = unwrap(expression)) {
            is FirPropertyAccessExpression ->
                if (unwrapped.explicitReceiver != null) null else unwrapped.calleeReference.toResolvedCallableSymbol() as? FirValueParameterSymbol
            is FirThisReceiverExpression -> receiver?.takeIf { unwrapped.calleeReference.boundSymbol == it }
            else -> null
        }
    }

    private fun unwrap(expression: FirExpression): FirExpression =
        if (expression is FirSmartCastExpression) unwrap(expression.originalExpression) else expression

    private fun sameType(session: FirSession, a: ConeKotlinType, b: ConeKotlinType): Boolean =
        AbstractTypeChecker.equalTypes(session.typeContext, a, b)
}

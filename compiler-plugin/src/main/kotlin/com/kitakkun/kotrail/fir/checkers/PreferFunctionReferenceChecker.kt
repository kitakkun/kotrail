package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.compat.qualifierClassId
import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.ReferenceForm
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirAnonymousFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.declaredFunctions
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.dispatchReceiverClassLookupTagOrNull
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirImplicitInvokeCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.references.toResolvedPropertySymbol
import org.jetbrains.kotlin.fir.references.toResolvedValueParameterSymbol
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.isSuspendOrKSuspendFunctionType
import org.jetbrains.kotlin.fir.types.resolvedType

/**
 * Asks for a callable reference where a lambda only forwards its parameters:
 *
 * ```kotlin
 * list.map { transform(it) }        // -> list.map(::transform)        (topLevel)
 * button.onClick { vm.submit() }    // -> button.onClick(vm::submit)   (bound)
 * list.map { it.name }              // -> list.map(User::name)         (typeQualified)
 * ```
 *
 * Only reported when the reference is guaranteed to resolve to the same call: parameters are
 * forwarded in order without extras or omissions, the callee has no varargs, type parameters,
 * or same-named overloads, the receiver (if any) is `this`, a `val`, an object, or the first
 * lambda parameter, and neither side is `@Composable` or mismatched on `suspend`. The forms a
 * project wants are selected with `preferFunctionReferences.forms`.
 */
object PreferFunctionReferenceChecker : FirAnonymousFunctionChecker(MppCheckerKind.Common) {
    private class Suggestion(val form: ReferenceForm, val text: String)

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirAnonymousFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.PREFER_FUNCTION_REFERENCES)) return
        if (!declaration.isLambda) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.receiverParameter != null) return

        val session = context.session
        val lambdaType = declaration.typeRef.coneType
        if (lambdaType.customAnnotations.any { it.toAnnotationClassId(session) == ComposeNames.COMPOSABLE }) return
        val lambdaSuspends = lambdaType.isSuspendOrKSuspendFunctionType(session)

        val statement = declaration.body?.statements?.singleOrNull() ?: return
        val expression = when (statement) {
            is FirReturnExpression -> if (statement.target.labeledElement === declaration) statement.result else return
            is FirExpression -> statement
            else -> return
        }.unwrapSmartCast()
        val parameters = declaration.valueParameters.map { it.symbol }

        val suggestion = when (expression) {
            is FirFunctionCall -> referenceForCall(expression, parameters, lambdaSuspends, session)
            is FirPropertyAccessExpression -> referenceForProperty(expression, parameters, session)
            else -> null
        } ?: return
        if (suggestion.form !in config.preferFunctionReferences.forms) return
        reportKotrail(source, KotrailDiagnostics.PREFER_FUNCTION_REFERENCE, suggestion.text)
    }

    private fun referenceForCall(
        call: FirFunctionCall,
        parameters: List<FirValueParameterSymbol>,
        lambdaSuspends: Boolean,
        session: FirSession,
    ): Suggestion? {
        // `{ block() }` on a function-typed value has no named callee to reference.
        if (call is FirImplicitInvokeCall) return null
        val callee = call.calleeReference.toResolvedFunctionSymbol() ?: return null
        if (callee.typeParameterSymbols.isNotEmpty()) return null
        if (callee.valueParameterSymbols.any { it.isVararg }) return null
        if (callee.isSuspend != lambdaSuspends) return null
        if (callee.isComposable(session)) return null
        if (hasOverloads(callee, session)) return null
        val arguments = call.arguments.map { it.unwrapArgument().unwrapSmartCast() }
        if (arguments.size != callee.valueParameterSymbols.size) return null

        val name = when (callee) {
            is FirNamedFunctionSymbol -> callee.name.asString()
            is FirConstructorSymbol -> callee.callableId.className?.shortName()?.asString() ?: return null
            else -> return null
        }
        val receiver = call.explicitReceiver?.unwrapSmartCast()
        val receiverParameter = receiver?.asLambdaParameter()?.takeIf { it in parameters }

        return when {
            // { it.f(a) } -> Type::f, the receiver being the first lambda parameter.
            receiverParameter != null -> {
                if (parameters.firstOrNull() != receiverParameter) return null
                if (!forwardsInOrder(arguments, parameters.drop(1))) return null
                val typeName = receiver.resolvedType.toRegularClassSymbol(session)?.takeIf { it.typeParameterSymbols.isEmpty() }
                    ?: return null
                Suggestion(ReferenceForm.TYPE_QUALIFIED, "${typeName.name.asString()}::$name")
            }
            receiver == null -> {
                if (!forwardsInOrder(arguments, parameters)) return null
                // An extension function called on an implicit receiver cannot be referenced as `::f`.
                if (callee.receiverParameterSymbol != null) return null
                Suggestion(ReferenceForm.TOP_LEVEL, "::$name")
            }
            else -> {
                if (!forwardsInOrder(arguments, parameters)) return null
                val prefix = stableReceiverText(receiver) ?: return null
                Suggestion(ReferenceForm.BOUND, "$prefix::$name")
            }
        }
    }

    private fun referenceForProperty(
        access: FirPropertyAccessExpression,
        parameters: List<FirValueParameterSymbol>,
        session: FirSession,
    ): Suggestion? {
        val receiver = access.explicitReceiver?.unwrapSmartCast() ?: return null
        val receiverParameter = receiver.asLambdaParameter() ?: return null
        if (parameters.size != 1 || parameters.single() != receiverParameter) return null
        val property = access.calleeReference.toResolvedPropertySymbol() ?: return null
        if (property.isLocal) return null
        val typeName = receiver.resolvedType.toRegularClassSymbol(session)?.takeIf { it.typeParameterSymbols.isEmpty() }
            ?: return null
        return Suggestion(ReferenceForm.TYPE_QUALIFIED, "${typeName.name.asString()}::${property.name.asString()}")
    }

    private fun forwardsInOrder(arguments: List<FirExpression>, parameters: List<FirValueParameterSymbol>): Boolean =
        arguments.size == parameters.size &&
            arguments.zip(parameters).all { (argument, parameter) -> argument.asLambdaParameter() == parameter }

    private fun FirExpression.asLambdaParameter(): FirValueParameterSymbol? =
        (this as? FirPropertyAccessExpression)?.calleeReference?.toResolvedValueParameterSymbol()

    /** `this`, an object or companion, or a plain `val` / parameter; anything else could be re-evaluated differently. */
    private fun stableReceiverText(receiver: FirExpression): String? = when (receiver) {
        is FirThisReceiverExpression -> "this"
        is FirResolvedQualifier -> receiver.qualifierClassId?.shortClassName?.asString()
        is FirPropertyAccessExpression -> {
            if (receiver.explicitReceiver != null) return null
            when (val variable = receiver.calleeReference.toResolvedVariableSymbol()) {
                is FirValueParameterSymbol -> variable.name.asString()
                is FirPropertySymbol -> if (variable.isVal) variable.name.asString() else null
                else -> null
            }
        }
        else -> null
    }

    private fun hasOverloads(callee: FirFunctionSymbol<*>, session: FirSession): Boolean {
        return when (callee) {
            // Constructor references need the class's constructor list; treated as ambiguous for now.
            is FirConstructorSymbol -> true
            is FirNamedFunctionSymbol -> {
                val owner = callee.dispatchReceiverClassLookupTagOrNull()?.toSymbol(session) as? FirClassSymbol<*>
                if (owner != null) {
                    owner.declaredFunctions(session).count { it.name == callee.name } > 1
                } else {
                    val id = callee.callableId
                    session.symbolProvider.getTopLevelFunctionSymbols(id.packageName, id.callableName).size > 1
                }
            }
            else -> true
        }
    }

    private fun FirExpression.unwrapSmartCast(): FirExpression =
        if (this is FirSmartCastExpression) originalExpression.unwrapSmartCast() else this
}

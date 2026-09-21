package com.kitakkun.kotrail.fir.compose

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirImplicitInvokeCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Whether a `Unit`-returning composable puts something on the screen, as far as its body says:
 * it calls, anywhere in its body including inside lambdas, a `Unit`-returning composable that is
 * not a pure runtime construct, or invokes a composable lambda it was handed (`content()`).
 *
 * The runtime constructs are the composables of the packages in [nonUiPackages]
 * (`androidx.compose.runtime` by default: `LaunchedEffect`, `SideEffect`, `DisposableEffect`,
 * `CompositionLocalProvider`, ...): the call itself emits nothing, but its lambdas are still
 * looked into, so `CompositionLocalProvider(...) { Text("x") }` emits and `LaunchedEffect(key) { }`
 * does not. A callee of this compilation is judged by its own body, recursively, so a wrapper
 * around an effect is as non-emitting as the effect; a callee from the classpath is taken as
 * emitting, since its body cannot be seen. A composable without a body (an abstract member) is
 * taken as non-emitting.
 *
 * What this exempts from a preview requirement: `*Effect` wrappers, probes that only read a
 * local, trackers that only register state. What it keeps: everything that draws.
 */
@OptIn(SymbolInternals::class)
fun FirNamedFunctionSymbol.emitsUi(session: FirSession, nonUiPackages: List<String>): Boolean =
    emitsUi(session, nonUiPackages, HashSet())

@OptIn(SymbolInternals::class)
private fun FirNamedFunctionSymbol.emitsUi(session: FirSession, nonUiPackages: List<String>, visiting: MutableSet<FirNamedFunctionSymbol>): Boolean {
    if (!origin.fromSource) return true
    val body = fir.body ?: return false
    // A recursive composable is taken as emitting rather than looping.
    if (!visiting.add(this)) return true
    val finder = UiCallFinder(session, nonUiPackages, visiting)
    body.accept(finder)
    visiting.remove(this)
    return finder.found
}

private class UiCallFinder(
    private val session: FirSession,
    private val nonUiPackages: List<String>,
    private val visiting: MutableSet<FirNamedFunctionSymbol>,
) : FirVisitorVoid() {
    var found = false

    override fun visitElement(element: FirElement) {
        if (found) return
        element.acceptChildren(this)
    }

    // FirVisitorVoid routes an implicit invoke to visitElement, not to visitFunctionCall.
    override fun visitImplicitInvokeCall(implicitInvokeCall: FirImplicitInvokeCall) {
        visitFunctionCall(implicitInvokeCall)
    }

    override fun visitFunctionCall(functionCall: FirFunctionCall) {
        if (found) return
        // `content()`: a composable lambda handed in is a slot, and a slot emits.
        if (functionCall is FirImplicitInvokeCall && functionCall.invokesComposableValue()) {
            found = true
            return
        }
        val callee = functionCall.calleeReference.toResolvedNamedFunctionSymbol()
        if (callee != null && callee.isComposable(session) && callee.resolvedReturnType.isUnit) {
            val isRuntimeConstruct = callee.callableId?.packageName?.asString()?.let { pkg -> nonUiPackages.any { pkg == it } } == true
            if (!isRuntimeConstruct && callee.emitsUi(session, nonUiPackages, visiting)) {
                found = true
                return
            }
        }
        functionCall.acceptChildren(this)
    }

    /** The invoked value's type, as declared on the parameter or property when it is one, carries `@Composable`. */
    private fun FirImplicitInvokeCall.invokesComposableValue(): Boolean {
        val receiver = (explicitReceiver ?: dispatchReceiver)?.let { if (it is FirSmartCastExpression) it.originalExpression else it } ?: return false
        val declaredType = (receiver as? FirQualifiedAccessExpression)?.calleeReference?.toResolvedCallableSymbol()?.resolvedReturnType
        val types = listOfNotNull(declaredType, receiver.resolvedType)
        return types.any { type ->
            type.isSomeFunctionType(session) && type.customAnnotations.any { it.toAnnotationClassId(session) == ComposeNames.COMPOSABLE }
        }
    }
}

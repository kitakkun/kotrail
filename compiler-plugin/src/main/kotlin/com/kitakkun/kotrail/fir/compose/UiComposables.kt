package com.kitakkun.kotrail.fir.compose

import com.kitakkun.kotrail.KotrailConfig
import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.inferred.FactArguments
import com.kitakkun.kotrail.fir.inferred.InferredFactService
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.inferred.InferredFactNames
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirImplicitInvokeCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Whether a `Unit`-returning composable puts something on the screen, as far as its body says:
 * it calls, anywhere in its body including inside lambdas, a `Unit`-returning composable that is
 * not a pure runtime construct, or invokes a composable lambda it was handed (`content()`).
 *
 * The runtime constructs are the composables of the packages in `compose.nonUiPackages`
 * (`androidx.compose.runtime` by default: `LaunchedEffect`, `SideEffect`, `DisposableEffect`,
 * `CompositionLocalProvider`, ...): the call itself emits nothing, but its lambdas are still
 * looked into, so `CompositionLocalProvider(...) { Text("x") }` emits and `LaunchedEffect(key) { }`
 * does not. A callee of this compilation is judged by its own body, recursively, so a wrapper
 * around an effect is as non-emitting as the effect; a callee whose body is not at hand (another
 * module's, or one of this module's files an incremental build did not compile again) by the
 * `drawsUi` fact its module wrote, and is taken as emitting without one. A composable without a
 * body (an abstract member) is taken as non-emitting. See [DrawsUiService].
 *
 * What this exempts from a preview requirement: `*Effect` wrappers, probes that only read a
 * local, trackers that only register state. What it keeps: everything that draws.
 */
fun FirNamedFunctionSymbol.emitsUi(session: FirSession): Boolean = session.drawsUiService.drawsUi(this)

/**
 * The fact behind [emitsUi], memoized per composable: whether a `Unit`-returning composable draws
 * anything. Written only when it does not (`value = ["false"]`), since a composable without the
 * fact is taken to draw, which is also what becomes of a library compiled without Kotrail, and of
 * a composable reached again from its own analysis.
 *
 * It is written whenever a rule that asks is on, and also whenever the compilation records its
 * UI composables for the preview coverage of another (`composablesDir`), whose record
 * [com.kitakkun.kotrail.fir.compose.checkers.ComposableManifestChecker] makes with it: an
 * incremental build reads it back from the module's own facts file for the composables of the
 * files it does not compile again.
 */
class DrawsUiService(session: FirSession) : InferredFactService<FirNamedFunctionSymbol, Boolean>(session) {
    override val rules: List<KotrailRule>
        get() = listOf(
            KotrailRule.COMPOSE_PREVIEW_REQUIRED,
            KotrailRule.COMPOSE_PREVIEW_COVERAGE,
            KotrailRule.COMPOSE_NO_TRAILING_CALLBACK,
            KotrailRule.COMPOSE_NO_CALLBACK_IN_MODEL,
        )
    override val annotation: ClassId get() = ComposeNames.INFERRED_DRAWS_UI
    override val factName: String get() = InferredFactNames.DRAWS_UI
    override val parameters: List<Name> get() = listOf(VALUE)
    override val empty: Boolean get() = true

    /** Whether [symbol], a `Unit`-returning composable, draws anything. */
    fun drawsUi(symbol: FirNamedFunctionSymbol): Boolean = of(symbol)

    override fun isOn(config: KotrailConfig): Boolean = super.isOn(config) || config.composablesDir != null

    override fun shouldWarm(symbol: FirNamedFunctionSymbol): Boolean = symbol.isComposable(session) && symbol.resolvedReturnType.isUnit

    override fun decode(arguments: FactArguments): Boolean = arguments.strings(VALUE).singleOrNull() != FALSE

    override fun encode(value: Boolean): List<List<String>>? = if (value) null else listOf(listOf(FALSE))

    @OptIn(SymbolInternals::class)
    override fun analyze(symbol: FirNamedFunctionSymbol): Boolean {
        val body = symbol.fir.body ?: return false
        val finder = UiCallFinder(session, session.kotrailConfig.compose.nonUiPackages, this)
        body.accept(finder)
        return finder.found
    }

    companion object {
        val VALUE: Name = Name.identifier("value")
        const val FALSE: String = "false"
    }
}

val FirSession.drawsUiService: DrawsUiService by FirSession.sessionComponentAccessor()

private class UiCallFinder(
    private val session: FirSession,
    private val nonUiPackages: List<String>,
    private val service: DrawsUiService,
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
            if (!isRuntimeConstruct && service.drawsUi(callee)) {
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

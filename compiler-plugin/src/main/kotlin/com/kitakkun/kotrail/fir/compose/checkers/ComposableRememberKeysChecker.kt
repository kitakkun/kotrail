package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Asks that the lambda of `remember`, `LaunchedEffect` and their kin be keyed on what it reads:
 *
 * ```kotlin
 * @Composable
 * fun Price(amount: Long, rate: Rate) {
 *     val text = remember { format(amount, rate) }        // reported: amount, rate
 *     LaunchedEffect(Unit) { analytics.view(amount) }     // reported: amount
 *     val text = remember(amount, rate) { format(amount, rate) }   // fine
 * }
 * ```
 *
 * A lambda keyed on nothing runs once and keeps the first value of whatever it captured: the
 * composable shows the old price, the effect reports the old amount, the callback stored in a
 * remembered holder calls the old handler. Nothing at the call site says so, which is why the
 * mistake survives review. What counts as captured is what belongs to the enclosing
 * composable: its parameters, its delegated locals (`by remember { mutableStateOf(...) }`,
 * `by state.collectAsState()`), and locals computed from those. A key covers a value when it is
 * a direct read of it.
 *
 * Left alone: reads inside `snapshotFlow { }` and `derivedStateOf { }`, which observe on their
 * own; a local from `rememberUpdatedState`, which is the sanctioned way to read the latest
 * value from a keyless effect; locals from other `remember*` calls and `CompositionLocal.current`
 * (a scope, a context), which are stable for the composition; a call whose lambda is not a
 * literal; and any call outside a composable.
 */
object ComposableRememberKeysChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    private const val REMEMBER_UPDATED_STATE = "androidx.compose.runtime.rememberUpdatedState"
    private val OBSERVING_LAMBDAS = setOf("androidx.compose.runtime.snapshotFlow", "androidx.compose.runtime.derivedStateOf")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val session = context.session
        val config = session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_REMEMBER_KEYS)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        val fqName = callee.callableId.asSingleFqName().asString()
        if (fqName !in config.compose.rememberKeysFunctions) return

        val arguments = expression.argumentList.arguments.map { it.unwrapArgument() }
        val lambda = (arguments.lastOrNull() as? FirAnonymousFunctionExpression)?.anonymousFunction ?: return
        val keys = arguments.dropLast(1).flatMap { argument ->
            if (argument is FirVarargArgumentsExpression) argument.arguments.map { it.unwrapArgument() } else listOf(argument)
        }

        val function = context.containingElements.lastOrNull { it is FirNamedFunction } as? FirNamedFunction ?: return
        if (!function.symbol.isComposable(session)) return
        val body = function.body ?: return

        val flagged = Flagged(config.compose.rememberKeysFunctions)
        function.valueParameters.forEach { flagged.add(it.symbol, it.name.asString()) }
        body.accept(LocalCollector(flagged, before = source.startOffset, lambda))

        val reads = ReadCollector(flagged)
        lambda.body?.accept(reads)
        if (reads.found.isEmpty()) return

        val covered = keys.mapNotNullTo(HashSet()) { it.unwrapped().readSymbol() }
        val missing = reads.found.entries.filter { it.key !in covered }.sortedBy { it.value.second }.map { it.value.first }
        if (missing.isEmpty()) return
        reportKotrail(source, KotrailDiagnostics.EFFECT_KEY_MISSING, callee.name.asString(), missing.joinToString(", "))
    }

    /** The symbols of the enclosing composable whose values a keyless lambda would freeze, with their names. */
    private class Flagged(private val keyedFunctions: List<String>) {
        private val names = linkedMapOf<FirBasedSymbol<*>, String>()

        fun add(symbol: FirBasedSymbol<*>, name: String) {
            names[symbol] = name
        }

        fun nameOf(symbol: FirBasedSymbol<*>): String? = names[symbol]

        fun contains(symbol: FirBasedSymbol<*>): Boolean = symbol in names

        /** Whether a local's delegate or initializer makes it stable for the composition rather than a captured value. */
        fun isStable(expression: FirExpression): Boolean {
            val call = expression as? FirFunctionCall
            if (call != null) {
                val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return false
                val fqName = callee.callableId.asSingleFqName().asString()
                if (fqName == REMEMBER_UPDATED_STATE) return true
                return callee.name.asString().startsWith("remember") && fqName !in keyedFunctions
            }
            val access = expression as? FirPropertyAccessExpression ?: return false
            return access.calleeReference.toResolvedCallableSymbol()?.name?.asString() == "current"
        }
    }

    /**
     * Flags the locals of the composable declared before the call and outside its lambda: a
     * delegated one (state), or one whose initializer reads something already flagged.
     */
    private class LocalCollector(private val flagged: Flagged, private val before: Int, private val lambda: FirAnonymousFunction) : FirVisitorVoid() {
        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
            if (anonymousFunction === lambda) return
            anonymousFunction.acceptChildren(this)
        }

        override fun visitProperty(property: FirProperty) {
            if (property.isLocal) {
                val start = property.source?.startOffset ?: Int.MAX_VALUE
                if (start < before) {
                    val delegate = property.delegate
                    val initializer = property.initializer
                    val flag = when {
                        delegate != null -> !flagged.isStable(delegate)
                        initializer != null -> !flagged.isStable(initializer) && initializer.readsFlagged()
                        else -> false
                    }
                    if (flag) flagged.add(property.symbol, property.name.asString())
                }
            }
            property.acceptChildren(this)
        }

        private fun FirExpression.readsFlagged(): Boolean {
            val reads = ReadCollector(flagged)
            accept(reads)
            return reads.found.isNotEmpty()
        }
    }

    /** The flagged symbols an expression reads, with each one's name and first offset; observing lambdas are skipped. */
    private class ReadCollector(private val flagged: Flagged) : FirVisitorVoid() {
        val found = linkedMapOf<FirBasedSymbol<*>, Pair<String, Int>>()

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            val fqName = functionCall.calleeReference.toResolvedNamedFunctionSymbol()?.callableId?.asSingleFqName()?.asString()
            if (fqName in OBSERVING_LAMBDAS) {
                // snapshotFlow { } and derivedStateOf { } observe what they read; only their receivers and other arguments count.
                functionCall.explicitReceiver?.accept(this)
                functionCall.argumentList.arguments.filter { it.unwrapArgument() !is FirAnonymousFunctionExpression }.forEach { it.accept(this) }
                return
            }
            functionCall.acceptChildren(this)
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            val symbol = propertyAccessExpression.calleeReference.toResolvedCallableSymbol()
            if (symbol != null && flagged.contains(symbol) && symbol !in found) {
                found[symbol] = flagged.nameOf(symbol)!! to (propertyAccessExpression.source?.startOffset ?: Int.MAX_VALUE)
            }
            propertyAccessExpression.acceptChildren(this)
        }
    }

    private fun FirExpression.unwrapped(): FirExpression = if (this is FirSmartCastExpression) originalExpression.unwrapped() else this

    /** A named, spread or lambda argument wrapper, when the argument list still carries one, is looked through. */
    private fun FirExpression.unwrapArgument(): FirExpression = if (this is FirWrappedArgumentExpression) expression else this

    private fun FirExpression.readSymbol(): FirCallableSymbol<*>? = (this as? FirPropertyAccessExpression)?.calleeReference?.toResolvedCallableSymbol()
}

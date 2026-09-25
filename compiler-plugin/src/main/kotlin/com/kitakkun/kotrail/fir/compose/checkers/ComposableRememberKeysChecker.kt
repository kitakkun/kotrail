package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirCheckedSafeCallSubject
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isNullableNothing
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.text
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * Asks that the lambda of `remember`, `LaunchedEffect` and their kin not freeze a value of the
 * enclosing composable that its keys do not cover:
 *
 * ```kotlin
 * @Composable
 * fun Price(amount: Long, events: Flow<Event>, onEvent: (Event) -> Unit) {
 *     val text = remember { format(amount) }                       // reported: amount, add it to the keys
 *     LaunchedEffect(Unit) { events.collect { onEvent(it) } }      // reported: onEvent, read it through rememberUpdatedState
 *     val text = remember(amount) { format(amount) }               // fine
 * }
 * ```
 *
 * A lambda keyed on nothing runs once and keeps the first value of whatever it captured. There
 * are two right fixes, and the message says which: a callback captured by an effect should be
 * read through `rememberUpdatedState`, since restarting the effect because a lambda changed
 * identity is itself the classic bug; a data value a `remember { }` computes from belongs in the
 * keys; a data value read by a long-lived effect body (a `collect`, a loop, `onDispose`) goes
 * in the keys if the work should restart when it changes, and through `rememberUpdatedState`
 * otherwise.
 *
 * What counts as captured is what belongs to the enclosing composable and can go stale: its
 * parameters and the locals computed from them, transitively. Not counted: a `State` (a
 * delegated local, a `State`-typed value), which is always current when read; a local from
 * `rememberUpdatedState`, any other `remember*` call, or a keyed call under check, and
 * `CompositionLocal.current`, all stable for the composition; a local derived only from values
 * the keys cover; writes; the initial value handed to `mutableStateOf(...)`; reads inside
 * `snapshotFlow { }` and `derivedStateOf { }`, which observe on their own. A read is covered by
 * a key that reads the same value, or whose text is the property path the read roots
 * (`remember(tx.request.url) { parse(tx.request.url) }`), at any depth of the lambda and through
 * safe calls. In an effect, a data value read only by a one-shot body is left alone, and so is
 * a callback called before the body's first suspension point or long-lived construct: both read
 * this composition's value once, which is what such effects are for. In a `remember { }`, an
 * argument handed to a constructor (`remember { SplitState(initialFraction) }`) is a seed like
 * the one handed to `mutableStateOf`.
 */
object ComposableRememberKeysChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    private val STATE = ClassId(FqName("androidx.compose.runtime"), Name.identifier("State"))
    private val OBSERVING_LAMBDAS = setOf("androidx.compose.runtime.snapshotFlow", "androidx.compose.runtime.derivedStateOf")
    private val SEEDS = setOf("mutableStateOf", "mutableIntStateOf", "mutableLongStateOf", "mutableFloatStateOf", "mutableDoubleStateOf")
    private val LONG_LIVED_LAMBDAS = setOf("collect", "collectLatest", "collectIndexed", "onEach", "onDispose", "awaitPointerEventScope", "withFrameNanos", "withFrameMillis")
    private val AWAIT_FOREVER = setOf("awaitCancellation")
    private val EFFECT_NAMES = setOf("LaunchedEffect", "DisposableEffect", "produceState")

    private const val ADVICE_KEYS = "add it to the keys"
    private const val ADVICE_UPDATED_STATE = "read it through rememberUpdatedState"
    private const val ADVICE_EITHER = "add it to the keys if the work should restart when it changes, otherwise read it through rememberUpdatedState"

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

        val flagged = Flagged(session, config.compose.rememberKeysFunctions)
        function.valueParameters.forEach { flagged.addParameter(it.symbol, it.name.asString(), it.symbol.resolvedReturnType) }
        body.accept(LocalCollector(flagged, before = source.startOffset, lambda))

        val coverage = Coverage(
            symbols = keys.mapNotNullTo(HashSet()) { it.unwrapped().readSymbol() },
            texts = keys.mapNotNullTo(HashSet()) { it.source?.text?.toString()?.trim() } + keys.mapNotNullTo(HashSet()) { it.canonicalPath() },
            flagged = flagged,
        )
        val isEffect = callee.name.asString() in EFFECT_NAMES
        // A callback called before the body's first suspension point or long-lived construct sees this composition's value.
        val syncPrefixEnd = if (isEffect) lambda.body?.statements?.firstOrNull { it.reachesSuspension() }?.source?.startOffset ?: Int.MAX_VALUE else Int.MIN_VALUE
        val reads = ReadCollector(flagged, coverage, isEffect, syncPrefixEnd)
        lambda.body?.accept(reads)
        if (reads.found.isEmpty()) return
        // A body that waits for cancellation keeps everything it read for as long as the effect lives.
        if (reads.awaitForever) reads.found.values.forEach { it.longLived = true }

        val missing = reads.found.values.sortedBy { it.offset }.mapNotNull { read ->
            val advice = when {
                flagged.isFunctionTyped(read.symbol) -> ADVICE_UPDATED_STATE
                !isEffect -> ADVICE_KEYS
                read.longLived -> ADVICE_EITHER
                else -> return@mapNotNull null
            }
            "${read.name} ($advice)"
        }
        if (missing.isEmpty()) return
        reportKotrail(source, KotrailDiagnostics.EFFECT_KEY_MISSING, callee.name.asString(), missing.joinToString("; "))
    }

    /** The values of the enclosing composable that a keyless lambda would freeze: parameters and the locals computed from them. */
    private class Flagged(private val session: FirSession, private val keyedFunctions: List<String>) {
        private class Info(val name: String, val isFunctionTyped: Boolean, val dependsOn: Set<FirBasedSymbol<*>>)

        private val infos = linkedMapOf<FirBasedSymbol<*>, Info>()

        fun addParameter(symbol: FirBasedSymbol<*>, name: String, type: ConeKotlinType) {
            if (type.isState()) return
            infos[symbol] = Info(name, type.isSomeFunctionType(session), emptySet())
        }

        fun addLocal(symbol: FirBasedSymbol<*>, name: String, type: ConeKotlinType, dependsOn: Set<FirBasedSymbol<*>>) {
            infos[symbol] = Info(name, type.isSomeFunctionType(session), dependsOn)
        }

        fun nameOf(symbol: FirBasedSymbol<*>): String? = infos[symbol]?.name

        fun contains(symbol: FirBasedSymbol<*>): Boolean = symbol in infos

        fun isFunctionTyped(symbol: FirBasedSymbol<*>): Boolean = infos[symbol]?.isFunctionTyped == true

        /** What a local was computed from, so that one derived only from covered values counts as covered. */
        fun dependenciesOf(symbol: FirBasedSymbol<*>): Set<FirBasedSymbol<*>> = infos[symbol]?.dependsOn.orEmpty()

        /** A `State` is always current when read; it cannot go stale. */
        fun ConeKotlinType.isState(): Boolean {
            val expanded = fullyExpandedType(session)
            val symbol = session.symbolProvider.getClassLikeSymbolByClassId(STATE) ?: return false
            val superType = STATE.constructClassLikeType(Array(symbol.typeParameterSymbols.size) { ConeStarProjection }, isMarkedNullable = true)
            return !expanded.isNullableNothing && AbstractTypeChecker.isSubtypeOf(session.typeContext, expanded, superType)
        }

        /**
         * Whether a local's initializer makes it stable for the composition rather than a captured
         * value: any `remember*` call (`rememberUpdatedState`, `rememberCoroutineScope`, a keyed
         * `remember`, and a keyless one, which is reported where it is), a keyed call under check,
         * or a `CompositionLocal.current` read.
         */
        fun isStable(expression: FirExpression): Boolean {
            val call = expression as? FirFunctionCall
            if (call != null) {
                val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return false
                if (callee.name.asString().startsWith("remember")) return true
                val fqName = callee.callableId.asSingleFqName().asString()
                return fqName in keyedFunctions && call.argumentList.arguments.size > 1
            }
            val access = expression as? FirPropertyAccessExpression ?: return false
            return access.calleeReference.toResolvedCallableSymbol()?.name?.asString() == "current"
        }
    }

    /**
     * Flags the plain locals of the composable declared before the call and outside its lambda
     * whose initializer reads something already flagged; a delegated local is a `State` and a
     * stable initializer is left alone.
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
            if (property.isLocal && property.delegate == null) {
                val start = property.source?.startOffset ?: Int.MAX_VALUE
                val initializer = property.initializer
                if (start < before && initializer != null && !flagged.isStable(initializer)) {
                    val type = property.returnTypeRef.coneType
                    val dependsOn = with(flagged) { if (type.isState()) emptySet() else initializer.flaggedReads() }
                    if (dependsOn.isNotEmpty()) flagged.addLocal(property.symbol, property.name.asString(), type, dependsOn)
                }
            }
            property.acceptChildren(this)
        }

        private fun FirExpression.flaggedReads(): Set<FirBasedSymbol<*>> {
            val reads = ReadCollector(flagged, Coverage.NONE, isEffect = false)
            accept(reads)
            return reads.found.keys
        }
    }

    /** What the call's keys cover: the symbols they read, and the property paths they spell. */
    private class Coverage(private val symbols: Set<FirBasedSymbol<*>>, private val texts: Set<String>, private val flagged: Flagged?) {
        fun coversSymbol(symbol: FirBasedSymbol<*>, seen: MutableSet<FirBasedSymbol<*>> = HashSet()): Boolean {
            if (symbol in symbols) return true
            if (flagged == null || !seen.add(symbol)) return false
            val dependencies = flagged.dependenciesOf(symbol)
            return dependencies.isNotEmpty() && dependencies.all { coversSymbol(it, seen) }
        }

        fun coversPath(text: String): Boolean = text in texts

        companion object {
            val NONE = Coverage(emptySet(), emptySet(), null)
        }
    }

    private class Read(val symbol: FirBasedSymbol<*>, val name: String, val offset: Int, var longLived: Boolean)

    /**
     * The flagged values a lambda body reads and does not cover: each with its first offset and
     * whether some read sits in a long-lived body. Writes, seeds, observing lambdas and covered
     * property paths are skipped.
     */
    private class ReadCollector(
        private val flagged: Flagged,
        private val coverage: Coverage,
        private val isEffect: Boolean,
        /** Offset of the first statement of an effect body that suspends or lives long; callbacks read before it, at the top level, are one-shot. */
        private val syncPrefixEnd: Int = Int.MIN_VALUE,
    ) : FirVisitorVoid() {
        val found = linkedMapOf<FirBasedSymbol<*>, Read>()
        private val ancestors = ArrayList<FirElement>()
        private var longLivedDepth = 0
        private var lambdaDepth = 0
        private var awaitsForever = false

        override fun visitElement(element: FirElement) {
            ancestors.add(element)
            element.acceptChildren(this)
            ancestors.removeAt(ancestors.lastIndex)
        }

        override fun visitVariableAssignment(variableAssignment: FirVariableAssignment) {
            // Assigning to a captured var is not a read of it.
            ancestors.add(variableAssignment)
            variableAssignment.rValue.accept(this)
            ancestors.removeAt(ancestors.lastIndex)
        }

        override fun visitDesugaredAssignmentValueReferenceExpression(desugaredAssignmentValueReferenceExpression: FirDesugaredAssignmentValueReferenceExpression) {
            // The `x` of `x += 1` and `x++`.
        }

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
            lambdaDepth++
            visitElement(anonymousFunction)
            lambdaDepth--
        }

        override fun visitWhileLoop(whileLoop: FirWhileLoop) = longLived { visitElement(whileLoop) }

        override fun visitDoWhileLoop(doWhileLoop: FirDoWhileLoop) = longLived { visitElement(doWhileLoop) }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            val callee = functionCall.calleeReference.toResolvedNamedFunctionSymbol()
            val name = callee?.name?.asString()
            val fqName = callee?.callableId?.asSingleFqName()?.asString()
            if (fqName in OBSERVING_LAMBDAS) {
                // snapshotFlow { } and derivedStateOf { } observe what they read; only their receivers and other arguments count.
                ancestors.add(functionCall)
                functionCall.explicitReceiver?.accept(this)
                functionCall.argumentList.arguments.filter { it.unwrapArgument() !is FirAnonymousFunctionExpression }.forEach { it.accept(this) }
                ancestors.removeAt(ancestors.lastIndex)
                return
            }
            val isConstructorInRemember = !isEffect && functionCall.calleeReference.toResolvedCallableSymbol() is FirConstructorSymbol
            if (name in SEEDS || isConstructorInRemember) {
                // `mutableStateOf(initial)`, or `remember { Holder(initial) }`: the initial value is meant to be taken once.
                ancestors.add(functionCall)
                functionCall.explicitReceiver?.accept(this)
                ancestors.removeAt(ancestors.lastIndex)
                return
            }
            if (name in AWAIT_FOREVER) awaitsForever = true
            if (name in LONG_LIVED_LAMBDAS) {
                // The receiver and plain arguments are read once; the lambda runs for as long as the effect lives.
                ancestors.add(functionCall)
                functionCall.explicitReceiver?.accept(this)
                for (argument in functionCall.argumentList.arguments) {
                    if (argument.unwrapArgument() is FirAnonymousFunctionExpression) longLived { argument.accept(this) } else argument.accept(this)
                }
                ancestors.removeAt(ancestors.lastIndex)
                return
            }
            visitElement(functionCall)
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            val symbol = propertyAccessExpression.calleeReference.toResolvedCallableSymbol()
            val offset = propertyAccessExpression.source?.startOffset ?: Int.MAX_VALUE
            val oneShotCallback = symbol != null && flagged.isFunctionTyped(symbol) && lambdaDepth == 0 && longLivedDepth == 0 && offset < syncPrefixEnd
            if (symbol != null && flagged.contains(symbol) && !oneShotCallback && !coverage.coversSymbol(symbol) && pathsRootedAt(propertyAccessExpression).none { coverage.coversPath(it) }) {
                val read = found.getOrPut(symbol) { Read(symbol, flagged.nameOf(symbol)!!, offset, longLived = false) }
                if (longLivedDepth > 0) read.longLived = true
            }
            visitElement(propertyAccessExpression)
        }

        /** Once the body reaches `awaitCancellation()`, everything it read stays captured for as long as the effect lives. */
        val awaitForever: Boolean get() = awaitsForever

        private inline fun longLived(block: () -> Unit) {
            longLivedDepth++
            block()
            longLivedDepth--
        }

        /**
         * Every qualified access chain the read roots, from the read itself outwards, as source text
         * and as a canonical path: `tx` inside `tx.request.url.length` gives `tx`, `tx.request`,
         * `tx.request.url` and `tx.request.url.length`, so that a key spelling any of them covers
         * the read. Safe calls are crossed (`session?.icon` is the path `session.icon`).
         */
        private fun pathsRootedAt(read: FirPropertyAccessExpression): List<String> {
            val paths = ArrayList<String>()
            var current: FirElement = read
            fun add(element: FirElement) {
                element.source?.text?.toString()?.trim()?.let { paths += it }
                (element as? FirExpression)?.canonicalPath()?.let { paths += it }
            }
            add(read)
            for (ancestor in ancestors.asReversed()) {
                val receiver = when (ancestor) {
                    is FirSafeCallExpression -> ancestor.receiver
                    is FirQualifiedAccessExpression -> ancestor.explicitReceiver
                    is FirSmartCastExpression -> ancestor.originalExpression
                    else -> null
                } ?: break
                if (receiver.unwrapped() !== current) break
                current = ancestor
                add(ancestor)
            }
            return paths
        }
    }

    /** Whether a statement of an effect body suspends, loops, or starts something that outlives it: from there on the body is no longer one-shot. */
    private fun FirStatement.reachesSuspension(): Boolean {
        var found = false
        accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (found) return
                element.acceptChildren(this)
            }

            override fun visitWhileLoop(whileLoop: FirWhileLoop) {
                found = true
            }

            override fun visitDoWhileLoop(doWhileLoop: FirDoWhileLoop) {
                found = true
            }

            override fun visitFunctionCall(functionCall: FirFunctionCall) {
                if (found) return
                val callee = functionCall.calleeReference.toResolvedCallableSymbol()
                val name = callee?.name?.asString()
                if ((callee as? FirNamedFunctionSymbol)?.isSuspend == true || name in LONG_LIVED_LAMBDAS || name in AWAIT_FOREVER) {
                    found = true
                    return
                }
                visitElement(functionCall)
            }
        })
        return found
    }

    /**
     * A qualified access as a dotted path of names, `session.icon` for `session?.icon` and
     * `tx.request.url` alike, so that a key and a read spell the same path however they are
     * written; null for anything that is not a chain of names rooted at a name.
     */
    private fun FirExpression.canonicalPath(): String? = when (val expression = unwrapArgument().unwrapped()) {
        is FirSafeCallExpression -> {
            val root = expression.receiver.canonicalPath() ?: return null
            val selector = expression.selector as? FirQualifiedAccessExpression ?: return null
            val name = selector.calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null
            "$root.$name" + if (selector is FirFunctionCall) "()" else ""
        }
        is FirCheckedSafeCallSubject -> null
        is FirPropertyAccessExpression -> {
            val name = expression.calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null
            when (val receiver = expression.explicitReceiver?.unwrapped()) {
                null -> name
                is FirCheckedSafeCallSubject -> null
                else -> receiver.canonicalPath()?.let { "$it.$name" }
            }
        }
        is FirFunctionCall -> {
            val name = expression.calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null
            when (val receiver = expression.explicitReceiver?.unwrapped()) {
                null -> null
                is FirCheckedSafeCallSubject -> null
                else -> receiver.canonicalPath()?.let { "$it.$name()" }
            }
        }
        else -> null
    }

    private fun FirExpression.unwrapped(): FirExpression = if (this is FirSmartCastExpression) originalExpression.unwrapped() else this

    /** A named, spread or lambda argument wrapper, when the argument list still carries one, is looked through. */
    private fun FirExpression.unwrapArgument(): FirExpression = if (this is FirWrappedArgumentExpression) expression else this

    private fun FirExpression.readSymbol(): FirCallableSymbol<*>? = (this as? FirPropertyAccessExpression)?.calleeReference?.toResolvedCallableSymbol()
}

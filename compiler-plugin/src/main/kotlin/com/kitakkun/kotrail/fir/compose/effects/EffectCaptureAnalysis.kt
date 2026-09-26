package com.kitakkun.kotrail.fir.compose.effects

import com.kitakkun.kotrail.fir.compose.isComposable
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
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
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
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
 * What a keyless lambda of `remember`, `LaunchedEffect` and their kin freezes, shared by the
 * `compose.rememberKeys` checker (which reports it at the call) and by [EffectCaptureService]
 * (which summarizes it per composable, so that callers of a helper that hides the effect can be
 * checked too).
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
internal object EffectCaptureAnalysis {
    private val STATE = ClassId(FqName("androidx.compose.runtime"), Name.identifier("State"))
    private val OBSERVING_LAMBDAS = setOf("androidx.compose.runtime.snapshotFlow", "androidx.compose.runtime.derivedStateOf")
    private val SEEDS = setOf("mutableStateOf", "mutableIntStateOf", "mutableLongStateOf", "mutableFloatStateOf", "mutableDoubleStateOf")
    private val LONG_LIVED_LAMBDAS = setOf("collect", "collectLatest", "collectIndexed", "onEach", "onDispose", "awaitPointerEventScope", "withFrameNanos", "withFrameMillis")
    private val AWAIT_FOREVER = setOf("awaitCancellation")
    private val EFFECT_NAMES = setOf("LaunchedEffect", "DisposableEffect", "produceState")

    /** A value a keyless lambda would freeze: its name, whether it is a callback, and whether some read sits in a long-lived body. */
    class Missing(val symbol: FirBasedSymbol<*>, val name: String, val isFunctionTyped: Boolean, val longLived: Boolean)

    /** What one keyed call freezes: the callee, whether it is an effect, and the values its keys do not cover. */
    class KeyedCall(val callee: FirNamedFunctionSymbol, val isEffect: Boolean, val missing: List<Missing>)

    /**
     * The values of [function] that the lambda of [call] (a call to one of [keyedFunctions])
     * freezes, or null when the call is not one under check. Every value is returned; whether a
     * data value in a one-shot effect body counts is the caller's decision, through [Missing.longLived].
     */
    fun keyedCall(session: FirSession, keyedFunctions: List<String>, function: FirNamedFunction, call: FirFunctionCall): KeyedCall? {
        val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return null
        val fqName = callee.callableId.asSingleFqName().asString()
        if (fqName !in keyedFunctions) return null
        val source = call.source ?: return null
        val arguments = call.argumentList.arguments.map { it.unwrapArgument() }
        val lambda = (arguments.lastOrNull() as? FirAnonymousFunctionExpression)?.anonymousFunction ?: return null
        val keys = arguments.dropLast(1).flatMap { argument ->
            if (argument is FirVarargArgumentsExpression) argument.arguments.map { it.unwrapArgument() } else listOf(argument)
        }
        if (function.body == null) return null

        val flagged = flaggedBefore(session, keyedFunctions, function, before = source.startOffset, except = lambda)
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
        // A body that waits for cancellation keeps everything it read for as long as the effect lives.
        if (reads.awaitForever) reads.found.values.forEach { it.longLived = true }
        val missing = reads.found.values.sortedBy { it.offset }.map { Missing(it.symbol, it.name, flagged.isFunctionTyped(it.symbol), it.longLived) }
        return KeyedCall(callee, isEffect, missing)
    }

    /**
     * Whether a value of a keyed call is a finding: a callback anywhere (after the one-shot
     * prefix, which the collector already skipped), a data value computed by `remember`, or a
     * data value in a long-lived effect body.
     */
    fun counts(call: KeyedCall, missing: Missing): Boolean = missing.isFunctionTyped || !call.isEffect || missing.longLived

    /**
     * The values of [function] that a lambda handed to another composable's captured parameter
     * would freeze: what it reads, with the whole lambda taken as long-lived and no keys to cover
     * anything. For a direct read of a flagged value, that value.
     */
    fun handedValues(session: FirSession, keyedFunctions: List<String>, function: FirNamedFunction, argument: FirExpression, at: Int): List<String> {
        val expression = argument.unwrapArgument().unwrapped()
        val lambda = (expression as? FirAnonymousFunctionExpression)?.anonymousFunction
        val flagged = flaggedBefore(session, keyedFunctions, function, before = at, except = lambda)
        if (lambda != null) {
            val reads = ReadCollector(flagged, Coverage.NONE, isEffect = true, syncPrefixEnd = Int.MIN_VALUE)
            reads.everythingLongLived = true
            lambda.body?.accept(reads)
            return reads.found.values.sortedBy { it.offset }.map { it.name }
        }
        val symbol = expression.readSymbol() ?: return emptyList()
        return listOfNotNull(flagged.nameOf(symbol))
    }

    /**
     * The parameters of [function] that its body captures for the life of an effect, by name:
     * those a keyed call of its own freezes (as [counts] decides), and those handed to another
     * composable whose [capturedOf] names the corresponding parameter, transitively.
     */
    fun capturedParameters(
        session: FirSession,
        keyedFunctions: List<String>,
        function: FirNamedFunction,
        capturedOf: (FirNamedFunctionSymbol) -> Set<String>,
    ): Set<String> {
        val body = function.body ?: return emptySet()
        val parameters = function.valueParameters.associateBy({ it.symbol as FirBasedSymbol<*> }, { it.name.asString() })
        val captured = linkedSetOf<String>()
        body.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                element.acceptChildren(this)
            }

            override fun visitFunctionCall(functionCall: FirFunctionCall) {
                val keyed = keyedCall(session, keyedFunctions, function, functionCall)
                if (keyed != null) {
                    for (missing in keyed.missing) {
                        if (!counts(keyed, missing)) continue
                        parameters[missing.symbol]?.let { captured += it }
                    }
                } else {
                    val callee = functionCall.calleeReference.toResolvedNamedFunctionSymbol()
                    if (callee != null && callee.isComposable(session)) {
                        val calleeCaptured = capturedOf(callee)
                        if (calleeCaptured.isNotEmpty()) {
                            val at = functionCall.source?.startOffset ?: Int.MAX_VALUE
                            for ((argument, parameter) in functionCall.resolvedArgumentMapping.orEmpty()) {
                                if (parameter.name.asString() !in calleeCaptured) continue
                                for (name in handedValues(session, keyedFunctions, function, argument, at)) {
                                    if (name in parameters.values) captured += name
                                }
                            }
                        }
                    }
                }
                functionCall.acceptChildren(this)
            }
        })
        return captured
    }

    /** The flagged values of [function] as seen at [before]: its parameters, and the plain locals declared earlier that derive from them. */
    private fun flaggedBefore(session: FirSession, keyedFunctions: List<String>, function: FirNamedFunction, before: Int, except: FirAnonymousFunction?): Flagged {
        val flagged = Flagged(session, keyedFunctions)
        function.valueParameters.forEach { flagged.addParameter(it.symbol, it.name.asString(), it.symbol.resolvedReturnType) }
        function.body?.accept(LocalCollector(flagged, before, except))
        return flagged
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
     * Flags the plain locals of the composable declared before [before] and outside [except]
     * whose initializer reads something already flagged; a delegated local is a `State` and a
     * stable initializer is left alone.
     */
    private class LocalCollector(private val flagged: Flagged, private val before: Int, private val except: FirAnonymousFunction?) : FirVisitorVoid() {
        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
            if (anonymousFunction === except) return
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
        /** A lambda handed to a helper that keeps it: every read in it is long-lived. */
        var everythingLongLived = false
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
                if (longLivedDepth > 0 || everythingLongLived) read.longLived = true
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
    fun FirExpression.unwrapArgument(): FirExpression = if (this is FirWrappedArgumentExpression) expression else this

    private fun FirExpression.readSymbol(): FirCallableSymbol<*>? = (this as? FirPropertyAccessExpression)?.calleeReference?.toResolvedCallableSymbol()
}

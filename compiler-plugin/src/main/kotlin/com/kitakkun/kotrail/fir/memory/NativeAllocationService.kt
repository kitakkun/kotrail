@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.memory

import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirAnonymousObjectExpression
import org.jetbrains.kotlin.fir.expressions.FirCall
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isNullableNothing
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * Per-session analysis of which functions create a native-backed object and let it out
 * unclosed, for the native-allocation-in-loop rule: `fun decode(frame): Bitmap = Bitmap().apply { ... }`
 * allocates per call as surely as the constructor does, and a loop that calls it is the same leak
 * one function away.
 *
 * A function allocates when its body (the lambdas of inline functions such as `synchronized`
 * and `run` included, since they run in place; every other lambda aside) creates one of `nativeAllocation.types`
 * or calls one of its `factories`, or calls a function that allocates, and the object is
 * neither the receiver of `use { }` (through scope functions), nor a local that the body closes
 * with `close()`, nor the memo of `cached ?: Bitmap().also { cached = it }`, which is created
 * once and kept. For a declaration on the classpath the `@InferredNativeAllocation` metadata
 * the plugin wrote is read; a library without it is taken as not allocating. Results are
 * memoized per symbol; a recorder warms the cache so that the IR writer only reads it.
 */
class NativeAllocationService(session: FirSession) : FirExtensionSessionComponent(session) {
    /**
     * What a function lets out: the type as written; the callee path from the function to the
     * allocation, innermost last, each name qualified by its class when it is a member
     * (`MirrorSurface.writeFrame`); and what the function does with it (`returns`, `keeps`, `lets go of`).
     */
    class Allocation(val type: String, val path: List<String>, val fate: String) {
        /** The path for a message: simple names, qualified only where a simple name repeats. */
        fun renderPath(): String {
            val simple = path.map { it.substringAfterLast('.') }
            val repeated = simple.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            return path.zip(simple).joinToString(" > ") { (qualified, plain) -> if (plain in repeated) qualified else plain }
        }
    }

    private val cache = HashMap<FirNamedFunctionSymbol, Allocation?>()
    private val visiting = HashSet<FirNamedFunctionSymbol>()

    /** The native-backed object [symbol] creates per call and lets out, or null. */
    fun allocates(symbol: FirNamedFunctionSymbol): Allocation? {
        if (cache.containsKey(symbol)) return cache[symbol]
        if (!visiting.add(symbol)) return null
        try {
            val result = compute(symbol)
            cache[symbol] = result
            return result
        } finally {
            visiting.remove(symbol)
        }
    }

    /** What [call] allocates when it is a constructor of a listed type, a listed factory, or a call to a function that allocates. */
    fun allocationOf(call: FirFunctionCall): Allocation? {
        val settings = session.kotrailConfig.nativeAllocation
        val callee = call.calleeReference.toResolvedCallableSymbol() ?: return null
        return when {
            callee is FirConstructorSymbol && settings.types.any { call.resolvedType.isSubtypeOf(ClassId.topLevel(FqName(it))) } ->
                Allocation(call.resolvedType.renderReadable(), emptyList(), "lets go of")
            callee.callableId?.asSingleFqName()?.asString() in settings.factories -> Allocation(call.resolvedType.renderReadable(), emptyList(), "lets go of")
            callee is FirNamedFunctionSymbol -> allocates(callee)?.let { Allocation(it.type, listOf(callee.qualifiedName()) + it.path, it.fate) }
            else -> null
        }
    }

    /** `MirrorSurface.writeFrame` for a member, `writeFrame` for a top-level function. */
    private fun FirNamedFunctionSymbol.qualifiedName(): String {
        val owner = callableId.className?.shortName()?.asString()
        return if (owner != null) "$owner.${name.asString()}" else name.asString()
    }

    private fun compute(symbol: FirNamedFunctionSymbol): Allocation? {
        if (!symbol.origin.fromSource) return declared(symbol)
        symbol.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
        val body = symbol.fir.body ?: return null
        val finder = Finder()
        body.accept(finder)
        return finder.leaked()
    }

    private fun declared(symbol: FirNamedFunctionSymbol): Allocation? {
        val annotation = symbol.resolvedAnnotationsWithArguments.firstOrNull { it.toAnnotationClassId(session) == INFERRED_NATIVE_ALLOCATION } ?: return null
        val argument = annotation.findArgumentByName(TYPES, returnFirstWhenNotFound = true) ?: return null
        val elements = when (argument) {
            is FirVarargArgumentsExpression -> argument.arguments
            is FirCall -> argument.argumentList.arguments
            else -> listOf(argument)
        }
        val type = elements.firstNotNullOfOrNull { (it.unwrapArgument() as? FirLiteralExpression)?.value as? String } ?: return null
        val path = annotation.findArgumentByName(PATH, returnFirstWhenNotFound = false)?.let { argument ->
            val items = when (argument) {
                is FirVarargArgumentsExpression -> argument.arguments
                is FirCall -> argument.argumentList.arguments
                else -> listOf(argument)
            }
            items.mapNotNull { (it.unwrapArgument() as? FirLiteralExpression)?.value as? String }
        }.orEmpty()
        val fate = elements.drop(1).firstNotNullOfOrNull { (it.unwrapArgument() as? FirLiteralExpression)?.value as? String } ?: "lets go of"
        return Allocation(type, path, fate)
    }

    /** Finds the allocations of a body and which of them are closed there. */
    private inner class Finder : FirVisitorVoid() {
        private val allocations = LinkedHashMap<FirFunctionCall, Allocation>()
        private val closed = HashSet<FirFunctionCall>()
        private val locals = HashMap<FirBasedSymbol<*>, FirFunctionCall>()
        private val closedLocals = HashSet<FirBasedSymbol<*>>()
        private val ancestors = ArrayList<FirElement>()

        fun leaked(): Allocation? = allocations.entries.firstOrNull { (call, _) ->
            call !in closed && locals.entries.none { (local, allocation) -> allocation === call && local in closedLocals }
        }?.let { (call, allocation) -> Allocation(allocation.type, allocation.path, fates[call] ?: "lets go of") }

        /** What the body does with each allocation: returned, kept in a property, or let go of. */
        private val fates = HashMap<FirFunctionCall, String>()

        override fun visitElement(element: FirElement) {
            ancestors.add(element)
            element.acceptChildren(this)
            ancestors.removeAt(ancestors.lastIndex)
        }

        /** Lambdas the enclosing call runs in place (`synchronized`, `withLock`, `run`, `apply`); every other lambda runs later or never. */
        private val inPlace = HashSet<FirAnonymousFunction>()

        override fun visitAnonymousFunctionExpression(anonymousFunctionExpression: FirAnonymousFunctionExpression) {
            if (anonymousFunctionExpression.anonymousFunction in inPlace) visitElement(anonymousFunctionExpression)
        }

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
            if (anonymousFunction in inPlace) visitElement(anonymousFunction)
        }

        override fun visitAnonymousObjectExpression(anonymousObjectExpression: FirAnonymousObjectExpression) {}

        override fun visitNamedFunction(namedFunction: FirNamedFunction) {}

        override fun visitElvisExpression(elvisExpression: FirElvisExpression) {
            // `latest ?: Bitmap().also { latest = it }`: created once and kept, not once per call.
            val memo = unwrapScopeChain(elvisExpression.rhs) as? FirFunctionCall
            val keeps = elvisExpression.lhs.unwrapped() is FirPropertyAccessExpression
            if (memo != null && keeps) closed += memo
            visitElement(elvisExpression)
        }

        override fun visitProperty(property: FirProperty) {
            val initializer = property.initializer?.let { unwrapScopeChain(it) } as? FirFunctionCall
            if (initializer != null) locals[property.symbol] = initializer
            visitElement(property)
        }

        override fun visitSafeCallExpression(safeCallExpression: FirSafeCallExpression) {
            // `data?.use { }`: closes the local like `data.use { }` does.
            val selector = safeCallExpression.selector as? FirFunctionCall
            if (selector != null && selector.fqName() in USE) {
                (safeCallExpression.receiver.unwrapped() as? FirPropertyAccessExpression)?.calleeReference?.toResolvedCallableSymbol()?.let { closedLocals += it }
            }
            visitElement(safeCallExpression)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            val allocation = allocationOf(functionCall)
            if (allocation != null) {
                allocations[functionCall] = allocation
                if (isClosedByUse(functionCall)) closed += functionCall
                fates[functionCall] = fateOf(functionCall)
            }
            val callee = functionCall.calleeReference.toResolvedNamedFunctionSymbol()
            if (callee != null && callee.isInline) {
                val mapping = functionCall.resolvedArgumentMapping
                for (argument in functionCall.argumentList.arguments) {
                    val lambda = (argument.unwrapArgument() as? FirAnonymousFunctionExpression)?.anonymousFunction ?: continue
                    val parameter = mapping?.get(argument.unwrapArgument()) ?: mapping?.get(argument)
                    if (parameter?.isNoinline != true) inPlace += lambda
                }
            }
            val name = functionCall.calleeReference.toResolvedCallableSymbol()?.name?.asString()
            val closesLocal = (name == "close" && functionCall.argumentList.arguments.isEmpty()) || functionCall.fqName() in USE
            if (closesLocal) {
                val receiver = (functionCall.explicitReceiver?.unwrapped() as? FirPropertyAccessExpression)?.calleeReference?.toResolvedCallableSymbol()
                if (receiver != null) closedLocals += receiver
            }
            visitElement(functionCall)
        }

        /**
         * `returns` when the object itself (through scope functions and safe calls) is what is returned,
         * `keeps` when it is assigned to a property (also through `also { x = it }`), else `lets go of`.
         */
        private fun fateOf(allocation: FirFunctionCall): String {
            var current: FirElement = allocation
            for (parent in ancestors.asReversed()) {
                when {
                    parent is FirReturnExpression && parent.result.unwrapped() === current -> return "returns"
                    parent is FirVariableAssignment && parent.rValue.unwrapped() === current ->
                        return if ((parent.lValue as? FirPropertyAccessExpression)?.calleeReference?.toResolvedCallableSymbol()?.let { it is FirPropertySymbol && !it.isLocal } == true) "keeps" else "lets go of"
                    parent is FirSafeCallExpression && parent.receiver.unwrapped() === current -> {
                        val selector = parent.selector as? FirFunctionCall ?: return "lets go of"
                        if (selector.fqName() !in SCOPE_FUNCTIONS) return "lets go of"
                        if (selector.assignsToProperty()) return "keeps"
                        current = parent
                    }
                    parent is FirFunctionCall && parent.explicitReceiver?.unwrapped() === current -> {
                        if (parent.fqName() !in SCOPE_FUNCTIONS) return "lets go of"
                        if (parent.assignsToProperty()) return "keeps"
                        current = parent
                    }
                    else -> return "lets go of"
                }
            }
            return "lets go of"
        }

        private fun FirFunctionCall.assignsToProperty(): Boolean {
            var found = false
            accept(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (found) return
                    if (element is FirVariableAssignment) {
                        val target = (element.lValue as? FirPropertyAccessExpression)?.calleeReference?.toResolvedCallableSymbol()
                        if (target is FirPropertySymbol && !target.isLocal) found = true
                    }
                    element.acceptChildren(this)
                }
            })
            return found
        }

        /** Whether the call is the receiver of `use { }`, directly, through scope functions, or through `?.` (a nullable factory result). */
        private fun isClosedByUse(allocation: FirFunctionCall): Boolean = isClosedByUse(allocation, ancestors.asReversed())

        /**
         * `Bitmap().apply { }.also { }` is the `Bitmap()` call; so is `factory()?.let { }`, and
         * `factory() ?: error("...")`, which only narrows the nullable result.
         */
        private fun unwrapScopeChain(expression: FirExpression): FirExpression {
            var current = expression.unwrapped()
            while (true) {
                current = when (current) {
                    is FirFunctionCall -> when (current.fqName()) {
                        in SCOPE_FUNCTIONS -> current.explicitReceiver?.unwrapped() ?: return current
                        // `bitmap.use { it.encodeToData() }`: the value is what the lambda ends with.
                        in RESULT_OF_LAMBDA -> current.lambdaResult() ?: return current
                        else -> return current
                    }
                    is FirSafeCallExpression -> if ((current.selector as? FirFunctionCall)?.fqName() in SCOPE_FUNCTIONS) current.receiver.unwrapped() else return current
                    is FirElvisExpression -> current.lhs.unwrapped()
                    else -> return current
                }
            }
        }

        /** The last expression of the lambda a call takes, when it is the call's result. */
        private fun FirFunctionCall.lambdaResult(): FirExpression? {
            val lambda = argumentList.arguments.lastOrNull()?.unwrapArgument() as? FirAnonymousFunctionExpression ?: return null
            val last = lambda.anonymousFunction.body?.statements?.lastOrNull() ?: return null
            return ((last as? FirReturnExpression)?.result ?: last as? FirExpression)?.unwrapped()
        }
    }

    private fun FirExpression.unwrapped(): FirExpression = (this as? FirSmartCastExpression)?.originalExpression ?: this

    private fun FirFunctionCall.fqName(): String? = calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString()

    /**
     * Whether [allocation] is the receiver of `use { }` among [parents] (innermost first): directly,
     * through scope functions (`Bitmap().apply { }.use { }`), or through a safe call on a nullable
     * factory result (`encodeToData()?.use(Data::bytes)`). Shared with the loop checker, which
     * walks the checker context's containing elements the same way.
     */
    fun isClosedByUse(allocation: FirFunctionCall, parents: List<FirElement>): Boolean {
        var current: FirElement = allocation
        for (parent in parents) {
            if (parent === current) continue
            val call = when {
                parent is FirSafeCallExpression && parent.receiver.unwrapped() === current -> parent.selector as? FirFunctionCall ?: return false
                parent is FirFunctionCall && parent.explicitReceiver?.unwrapped() === current -> parent
                else -> return false
            }
            val name = call.fqName() ?: return false
            if (name in USE) return true
            if (name !in SCOPE_FUNCTIONS) return false
            current = parent
        }
        return false
    }

    private fun ConeKotlinType.isSubtypeOf(classId: ClassId): Boolean {
        val expanded = fullyExpandedType(session)
        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) ?: return false
        val superType = classId.constructClassLikeType(Array(symbol.typeParameterSymbols.size) { ConeStarProjection }, isMarkedNullable = true)
        return !expanded.isNullableNothing && AbstractTypeChecker.isSubtypeOf(session.typeContext, expanded, superType)
    }

    companion object {
        val INFERRED_NATIVE_ALLOCATION = ClassId(FqName("com.kitakkun.kotrail.memory"), Name.identifier("InferredNativeAllocation"))
        private val TYPES = Name.identifier("types")
        private val PATH = Name.identifier("path")
        val USE = setOf("kotlin.use", "kotlin.io.use", "kotlin.AutoCloseable.use")

        /** Calls whose value is their lambda's last expression: what a `use`, `let` or `run` hands back. */
        private val RESULT_OF_LAMBDA = USE + setOf("kotlin.let", "kotlin.run", "kotlin.with")
        val SCOPE_FUNCTIONS = setOf("kotlin.apply", "kotlin.also", "kotlin.let", "kotlin.run", "kotlin.takeIf", "kotlin.takeUnless")
    }
}

val FirSession.nativeAllocationService: NativeAllocationService by FirSession.sessionComponentAccessor()

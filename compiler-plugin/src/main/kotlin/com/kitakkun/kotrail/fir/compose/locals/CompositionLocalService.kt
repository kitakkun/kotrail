@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.compose.locals

import com.kitakkun.kotrail.KotrailCompositionLocalKnowledge
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.declarations.processAllDeclarations
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirCollectionLiteral
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirImplicitInvokeCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirThrowExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.references.toResolvedPropertySymbol
import org.jetbrains.kotlin.fir.references.toResolvedValueParameterSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.isNothing
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * What a piece of composable code needs from and gives to the composition:
 *
 * - [reads]: every composition local read somewhere below without being provided on the way,
 *   required or not, keyed by the local's fully qualified property name, each with the chain
 *   of callee names that leads to the read (empty for a read in the analyzed body itself).
 *   Whether a read is an error is decided at the root, where the project's settings apply, so
 *   the metadata records all of them.
 * - [provides]: for each composable lambda parameter of the analyzed function, the locals
 *   provided around every invocation of that parameter, so that a caller's lambda may read them.
 */
class LocalsAnalysis(
    val reads: Map<String, List<String>>,
    val provides: Map<String, Set<String>>,
    /**
     * The reads that are required as far as the code that read them can tell: decided where the
     * local is visible, and carried with the read, so that a root in another module knows it
     * even for a `private` local read through a public getter.
     */
    val required: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = LocalsAnalysis(emptyMap(), emptyMap())
    }
}

/**
 * Per-session analysis of composition locals: which locals are required, and what each
 * composable reads and provides. Shared by the FIR checkers and, through the FIR declaration
 * attached to each IR declaration, by the IR metadata writer.
 *
 * A callee is understood through, in order: the project's knowledge base entry, its declared
 * `@InferredCompositionLocals` metadata (another module compiled with Kotrail), or its source
 * body (this module). A composable none of these covers is taken to read and provide nothing.
 */
class CompositionLocalService(session: FirSession) : FirExtensionSessionComponent(session) {
    private val settings get() = session.kotrailConfig.compose.compositionLocals
    private val cache = HashMap<FirNamedFunctionSymbol, LocalsAnalysis>()
    private val visiting = HashSet<FirNamedFunctionSymbol>()
    private val getterCache = HashMap<FirPropertySymbol, LocalsAnalysis>()
    private val visitingGetters = HashSet<FirPropertySymbol>()
    private val requiredCache = HashMap<FirPropertySymbol, Boolean>()
    private val sourceRequiredCache = HashMap<FirPropertySymbol, Boolean>()

    /** Whether reading [local] without a provider is an error: its default throws, or the project said so. */
    fun isRequired(local: FirPropertySymbol): Boolean = requiredCache.getOrPut(local) { computeRequired(local) }

    /**
     * The same for a local known by name, as the metadata and the knowledge base spell it. A
     * name that resolves to no property (a knowledge-base entry for a local this compilation
     * cannot see) is required only when `compose.compositionLocals.required` lists it.
     */
    fun isRequired(fqName: String): Boolean {
        if (fqName in settings.required) return true
        return resolveLocal(fqName)?.let { isRequired(it) } ?: false
    }

    /** [isRequired] for a read that an analysis carried: what the reader knew, or what this compilation can tell. */
    fun isRequired(fqName: String, analysis: LocalsAnalysis): Boolean = fqName in analysis.required || isRequired(fqName)

    /**
     * Whether [local] is required because of its own declaration, which is what the IR writer
     * records. Cached, because the initializer it looks at is released after Fir2Ir: the property
     * warm-up checker fills the cache while FIR is available, and the IR writer only reads it.
     */
    fun isRequiredBySource(local: FirPropertySymbol): Boolean =
        sourceRequiredCache.getOrPut(local) { local.origin.fromSource && defaultThrows(local) }

    /** The analysis of [symbol], cached; bodies are only available during the FIR phase. */
    fun analysis(symbol: FirNamedFunctionSymbol): LocalsAnalysis {
        cache[symbol]?.let { return it }
        if (!visiting.add(symbol)) return LocalsAnalysis.EMPTY
        try {
            val result = compute(symbol)
            cache[symbol] = result
            return result
        } finally {
            visiting.remove(symbol)
        }
    }

    /** What [body] reads when nothing above it provides anything, for entry-point lambdas and roots. */
    fun readsBelow(body: FirElement): LocalsAnalysis {
        val collector = Collector(ownParameters = emptySet())
        body.accept(collector)
        return LocalsAnalysis(collector.reads, emptyMap(), collector.required)
    }

    /**
     * The analysis of a composable property getter (`val colors: Colors @Composable get() = LocalColors.current`):
     * a reader like any composable, so that a private local behind a public getter is still seen.
     */
    fun getterAnalysis(property: FirPropertySymbol): LocalsAnalysis {
        getterCache[property]?.let { return it }
        if (!visitingGetters.add(property)) return LocalsAnalysis.EMPTY
        try {
            val result = computeGetter(property)
            getterCache[property] = result
            return result
        } finally {
            visitingGetters.remove(property)
        }
    }

    private fun computeGetter(property: FirPropertySymbol): LocalsAnalysis {
        declaredMetadata(property.resolvedAnnotationsWithArguments)?.let { return it }
        if (!property.origin.fromSource) return LocalsAnalysis.EMPTY
        val getter = property.getterSymbol ?: return LocalsAnalysis.EMPTY
        property.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
        val body = getter.fir.body ?: return LocalsAnalysis.EMPTY
        val collector = Collector(ownParameters = emptySet())
        body.accept(collector)
        return LocalsAnalysis(collector.reads, emptyMap(), collector.required)
    }

    /** A top-level or object-member property from its fully qualified name, or `null`. */
    private fun resolveLocal(fqName: String): FirPropertySymbol? {
        val parts = fqName.split('.')
        if (parts.size < 2) return null
        val name = Name.identifier(parts.last())
        val provider = session.symbolProvider
        provider.getTopLevelPropertySymbols(FqName(parts.dropLast(1).joinToString(".")), name)
            .firstOrNull()?.let { return it }
        // `com.acme.Locals.LocalX`: try every split into package and class names, shortest package first.
        for (packageSize in 0 until parts.size - 1) {
            val packageName = FqName(parts.take(packageSize).joinToString("."))
            val relative = FqName(parts.subList(packageSize, parts.size - 1).joinToString("."))
            val classSymbol = provider.getClassLikeSymbolByClassId(ClassId(packageName, relative, isLocal = false)) as? FirRegularClassSymbol
                ?: continue
            var found: FirPropertySymbol? = null
            classSymbol.processAllDeclarations(session) { member ->
                if (found == null && member is FirPropertySymbol && member.name == name) found = member
            }
            found?.let { return it }
        }
        return null
    }

    private fun computeRequired(local: FirPropertySymbol): Boolean {
        val fqName = local.localFqName() ?: return false
        if (fqName in settings.required) return true
        if (local.hasAnnotation(CompositionLocalNames.REQUIRED_COMPOSITION_LOCAL, session)) return true
        if (local.hasAnnotation(CompositionLocalNames.INFERRED_REQUIRED_COMPOSITION_LOCAL, session)) return true
        return isRequiredBySource(local)
    }

    /** `compositionLocalOf { error("...") }`, `staticCompositionLocalOf { noLocalProvidedFor("X") }`: the default lambda ends by throwing. */
    private fun defaultThrows(local: FirPropertySymbol): Boolean {
        local.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
        val factory = local.fir.initializer as? FirFunctionCall ?: return false
        if (factory.calleeReference.toResolvedNamedFunctionSymbol()?.callableId !in CompositionLocalNames.FACTORIES) return false
        val lambda = factory.arguments.lastOrNull()?.unwrapArgument() as? FirAnonymousFunctionExpression ?: return false
        val last = (lambda.anonymousFunction.body as? FirBlock)?.statements?.lastOrNull() ?: return false
        val expression = (last as? FirReturnExpression)?.result ?: last
        return expression is FirThrowExpression || (expression is FirExpression && expression.resolvedType.isNothing)
    }

    private fun compute(symbol: FirNamedFunctionSymbol): LocalsAnalysis {
        settings.known[symbol.callableId.asSingleFqName().asString()]?.let { return it.toAnalysis() }
        declaredMetadata(symbol)?.let { return it }
        if (!symbol.origin.fromSource) return LocalsAnalysis.EMPTY
        symbol.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
        val body = symbol.fir.body ?: return LocalsAnalysis.EMPTY
        val collector = Collector(ownParameters = symbol.valueParameterSymbols.toSet())
        body.accept(collector)
        return LocalsAnalysis(collector.reads, collector.provides.mapValues { it.value ?: emptySet() }, collector.required)
    }

    private fun KotrailCompositionLocalKnowledge.toAnalysis(): LocalsAnalysis =
        LocalsAnalysis(reads.associateWith { emptyList() }, provides)

    private fun declaredMetadata(symbol: FirNamedFunctionSymbol): LocalsAnalysis? = declaredMetadata(symbol.resolvedAnnotationsWithArguments)

    /** The metadata the IR writer put on a function or a property: a read ending in `!` is one the reader found required. */
    private fun declaredMetadata(annotations: List<FirAnnotation>): LocalsAnalysis? {
        val annotation = annotations
            .firstOrNull { it.toAnnotationClassId(session) == CompositionLocalNames.INFERRED_COMPOSITION_LOCALS }
            ?: return null
        val reads = strings(annotation.findArgumentByName(CompositionLocalNames.READS_PARAM, returnFirstWhenNotFound = false))
        val provides = strings(annotation.findArgumentByName(CompositionLocalNames.PROVIDES_PARAM, returnFirstWhenNotFound = false))
        return LocalsAnalysis(
            reads = reads.associate { it.removeSuffix(REQUIRED_MARK) to emptyList() },
            provides = provides.mapNotNull { entry ->
                val parts = entry.split(':', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() },
            required = reads.filter { it.endsWith(REQUIRED_MARK) }.map { it.removeSuffix(REQUIRED_MARK) }.toSet(),
        )
    }

    private fun strings(expression: FirExpression?): List<String> {
        val elements = when (val expr = expression?.unwrapArgument()) {
            null -> emptyList()
            is FirCollectionLiteral -> expr.arguments
            is FirVarargArgumentsExpression -> expr.arguments
            is FirFunctionCall -> expr.arguments // arrayOf(...)
            else -> emptyList()
        }
        return elements.mapNotNull { (it.unwrapArgument() as? FirLiteralExpression)?.value as? String }
    }

    /**
     * Walks a body keeping track of the locals provided around the current point. A read of a
     * local outside such a scope is recorded; a call brings in the callee's reads, and its
     * composable lambda arguments are walked with whatever the callee provides to that
     * parameter added to the scope.
     */
    private inner class Collector(private val ownParameters: Set<FirValueParameterSymbol>) : FirVisitorVoid() {
        val reads = LinkedHashMap<String, List<String>>()

        /** The reads whose local this code can see is required. */
        val required = HashSet<String>()

        /** Locals provided around every invocation of an own lambda parameter; `null` until the first invocation. */
        val provides = HashMap<String, Set<String>?>()

        private var inScope: Set<String> = emptySet()

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            val local = propertyAccessExpression.readLocal()
            if (local != null) {
                val fqName = local.localFqName()
                if (fqName != null && fqName !in inScope) {
                    reads.putIfAbsent(fqName, emptyList())
                    if (isRequired(local)) required += fqName
                }
            }
            // `Theme.colors`, a composable getter: what it reads is read here.
            val property = propertyAccessExpression.calleeReference.toResolvedPropertySymbol()
            if (property != null && local == null && property.getterSymbol?.isComposable(session) == true) {
                merge(getterAnalysis(property), property.name.asString())
            }
            propertyAccessExpression.acceptChildren(this)
        }

        /** Brings a callee's reads (those not provided here) into this analysis, under [via] in the path. */
        private fun merge(calleeAnalysis: LocalsAnalysis, via: String) {
            for ((local, path) in calleeAnalysis.reads) {
                if (local in inScope) continue
                reads.putIfAbsent(local, listOf(via) + path)
                if (local in calleeAnalysis.required) required += local
            }
        }

        // FirVisitorVoid sends every node kind to visitElement unless its own method is overridden,
        // so an implicit invoke (`content()`) has to be routed to the function-call handling by hand.
        override fun visitImplicitInvokeCall(implicitInvokeCall: FirImplicitInvokeCall) {
            visitFunctionCall(implicitInvokeCall)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            if (functionCall is FirImplicitInvokeCall) {
                val parameter = functionCall.invokedOwnParameter()
                if (parameter != null) {
                    val name = parameter.name.asString()
                    provides[name] = provides[name]?.intersect(inScope) ?: inScope
                }
                functionCall.acceptChildren(this)
                return
            }
            val callee = functionCall.calleeReference.toResolvedNamedFunctionSymbol()
            val calleeAnalysis = when {
                callee == null -> null
                callee.callableId == CompositionLocalNames.COMPOSITION_LOCAL_PROVIDER -> LocalsAnalysis(
                    reads = emptyMap(),
                    provides = functionCall.contentParameters().associateWith { functionCall.providedLocals() },
                )
                callee.isComposable(session) -> analysis(callee)
                else -> null
            }

            functionCall.explicitReceiver?.accept(this)
            val mapping = functionCall.resolvedArgumentMapping
            for (argument in functionCall.arguments) {
                val elements = (argument.unwrapArgument() as? FirVarargArgumentsExpression)?.arguments ?: listOf(argument)
                for (element in elements) {
                    val lambda = (element.unwrapArgument() as? FirAnonymousFunctionExpression)?.anonymousFunction
                    val parameter = mapping?.get(argument)
                    val passedThrough = (element.unwrapArgument() as? FirPropertyAccessExpression)
                        ?.calleeReference?.toResolvedValueParameterSymbol()?.takeIf { it in ownParameters }
                    if (lambda != null && parameter != null) {
                        val extra = calleeAnalysis?.provides?.get(parameter.name.asString()).orEmpty()
                        withScope(inScope + extra) { lambda.accept(this) }
                    } else if (passedThrough != null && parameter != null) {
                        // `Theme(content = content)`: the callee invokes our parameter inside whatever it provides to its own.
                        val extra = calleeAnalysis?.provides?.get(parameter.name.asString()).orEmpty()
                        val name = passedThrough.name.asString()
                        provides[name] = provides[name]?.intersect(inScope + extra) ?: (inScope + extra)
                    } else {
                        element.accept(this)
                    }
                }
            }

            if (calleeAnalysis != null && callee != null) merge(calleeAnalysis, callee.name.asString())
        }

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
            // A lambda walked here was not an argument of a call handled above (a local `val`, a
            // lambda inside a provider argument, ...): its reads count for the enclosing body.
            anonymousFunction.body?.accept(this)
        }

        private inline fun withScope(scope: Set<String>, block: () -> Unit) {
            val saved = inScope
            inScope = scope
            try {
                block()
            } finally {
                inScope = saved
            }
        }

        /** `LocalX.current`: the property `LocalX` when this is a read of `current` on a composition local. */
        private fun FirPropertyAccessExpression.readLocal(): FirPropertySymbol? {
            val callee = calleeReference.toResolvedPropertySymbol() ?: return null
            if (callee.name != CompositionLocalNames.CURRENT) return null
            val owner = callee.dispatchReceiverType?.classId ?: return null
            if (owner != CompositionLocalNames.COMPOSITION_LOCAL && owner != CompositionLocalNames.PROVIDABLE_COMPOSITION_LOCAL) return null
            return explicitReceiver?.compositionLocal()
        }

        private fun FirExpression.compositionLocal(): FirPropertySymbol? {
            val access = unwrapArgument() as? FirPropertyAccessExpression ?: return null
            val symbol = access.calleeReference.toResolvedPropertySymbol() ?: return null
            val type = symbol.resolvedReturnType.classId ?: return null
            if (type != CompositionLocalNames.COMPOSITION_LOCAL && type != CompositionLocalNames.PROVIDABLE_COMPOSITION_LOCAL) return null
            return symbol
        }

        /** `content()` where `content` is a parameter of the function being analyzed. */
        private fun FirImplicitInvokeCall.invokedOwnParameter(): FirValueParameterSymbol? {
            val receiver = explicitReceiver as? FirPropertyAccessExpression ?: return null
            val parameter = receiver.calleeReference.toResolvedValueParameterSymbol() ?: return null
            return parameter.takeIf { it in ownParameters }
        }

        /** The names of the function-typed parameters of a `CompositionLocalProvider` call, normally just `content`, whether given a lambda or passed a parameter through. */
        private fun FirFunctionCall.contentParameters(): List<String> {
            val mapping = resolvedArgumentMapping ?: return emptyList()
            return mapping.entries
                .filter { (_, parameter) -> parameter.returnTypeRef.coneType.isSomeFunctionType(session) }
                .map { (_, parameter) -> parameter.name.asString() }
        }

        /** The locals of the `LocalX provides value` / `providesDefault` arguments of a `CompositionLocalProvider` call. */
        private fun FirFunctionCall.providedLocals(): Set<String> {
            val provided = LinkedHashSet<String>()
            for (argument in arguments) {
                val elements = (argument.unwrapArgument() as? FirVarargArgumentsExpression)?.arguments ?: listOf(argument)
                for (element in elements) {
                    val call = element.unwrapArgument() as? FirFunctionCall ?: continue
                    val name = call.calleeReference.toResolvedNamedFunctionSymbol()?.name ?: continue
                    if (name != CompositionLocalNames.PROVIDES && name != CompositionLocalNames.PROVIDES_DEFAULT) continue
                    val local = call.explicitReceiver?.compositionLocal() ?: continue
                    local.localFqName()?.let { provided += it }
                }
            }
            return provided
        }
    }
}

/** Appended to a read in the metadata when the reader found the local required. */
internal const val REQUIRED_MARK = "!"

val FirSession.compositionLocalService: CompositionLocalService by FirSession.sessionComponentAccessor()

/** A local's fully qualified property name, as the knowledge base and the metadata spell it; `null` for a local variable. */
internal fun FirPropertySymbol.localFqName(): String? = callableId?.asSingleFqName()?.asString()

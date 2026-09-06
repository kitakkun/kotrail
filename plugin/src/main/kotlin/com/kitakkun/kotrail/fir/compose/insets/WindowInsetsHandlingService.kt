@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.compose.insets

import com.kitakkun.kotrail.compose.insets.InsetsAnalysis
import com.kitakkun.kotrail.compose.insets.InsetsSet
import com.kitakkun.kotrail.compose.insets.Sides
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirCollectionLiteral
import org.jetbrains.kotlin.fir.expressions.FirEnumEntryDeserializedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.references.toResolvedEnumEntrySymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.CallableId

/**
 * Per-session analysis of which window insets a composable handles. Shared by the FIR checkers
 * and, through the FIR declaration attached to each IR function, by the IR metadata writer.
 */
class WindowInsetsHandlingService(session: FirSession) : FirExtensionSessionComponent(session) {
    private val cache = HashMap<FirNamedFunctionSymbol, InsetsAnalysis>()
    private val visiting = HashSet<FirNamedFunctionSymbol>()

    fun isComposable(symbol: FirNamedFunctionSymbol): Boolean =
        symbol.hasAnnotation(WindowInsetsNames.COMPOSABLE, session)

    /** The contract declared through `@HandlesWindowInsets`, or `null` when the function has none. */
    fun declaredContract(symbol: FirNamedFunctionSymbol): InsetsSet? {
        val annotations = symbol.resolvedAnnotationsWithArguments
            .filter { it.toAnnotationClassId(session) == WindowInsetsNames.HANDLES_WINDOW_INSETS }
        if (annotations.isEmpty()) return null
        return annotations.fold(InsetsSet.EMPTY) { acc, annotation -> acc.union(contractOf(annotation)) }
    }

    /** What [symbol] handles, following declared contracts, inferred metadata, the knowledge base, and source bodies. */
    fun handledInsets(symbol: FirNamedFunctionSymbol): InsetsAnalysis {
        cache[symbol]?.let { return it }
        if (!visiting.add(symbol)) return InsetsAnalysis.EMPTY
        try {
            val result = compute(symbol)
            cache[symbol] = result
            return result
        } finally {
            visiting.remove(symbol)
        }
    }

    /** What an arbitrary expression (typically a `Modifier` chain) handles. */
    fun handledByExpression(expression: FirExpression): InsetsAnalysis {
        val collector = HandlingCollector()
        expression.accept(collector)
        return collector.result
    }

    private fun compute(symbol: FirNamedFunctionSymbol): InsetsAnalysis {
        declaredContract(symbol)?.let { return InsetsAnalysis(it, unverifiable = false) }
        inferredMetadata(symbol)?.let { return InsetsAnalysis(it, unverifiable = false) }
        WindowInsetsNames.KNOWN_LIBRARY_COMPOSABLES[symbol.callableId]?.let { return InsetsAnalysis(it, unverifiable = false) }
        return analyzeBody(symbol)
    }

    private fun inferredMetadata(symbol: FirNamedFunctionSymbol): InsetsSet? {
        val annotation = symbol.resolvedAnnotationsWithArguments
            .firstOrNull { it.toAnnotationClassId(session) == WindowInsetsNames.INFERRED_WINDOW_INSETS_HANDLING }
            ?: return null
        val argument = annotation.findArgumentByName(WindowInsetsNames.HANDLED_PARAM, returnFirstWhenNotFound = false)
        val strings = arrayElements(argument).mapNotNull { (it as? FirLiteralExpression)?.value as? String }
        return InsetsSet.decode(strings)
    }

    private fun analyzeBody(symbol: FirNamedFunctionSymbol): InsetsAnalysis {
        if (!symbol.origin.fromSource) return InsetsAnalysis.EMPTY
        symbol.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
        // Bodies are released after Fir2Ir, so this must run during the FIR phase. The checkers
        // warm the cache for every composable; the IR writer only reads cached results.
        val body = symbol.fir.body ?: return InsetsAnalysis.EMPTY
        val collector = HandlingCollector()
        body.accept(collector)
        return collector.result
    }

    private fun contractOf(annotation: FirAnnotation): InsetsSet {
        val typeName = enumEntryName(annotation.findArgumentByName(WindowInsetsNames.TYPE_PARAM)) ?: return InsetsSet.EMPTY
        val sidesArgument = annotation.findArgumentByName(WindowInsetsNames.SIDES_PARAM, returnFirstWhenNotFound = false)
        val sides = if (sidesArgument == null) {
            Sides.ALL
        } else {
            arrayElements(sidesArgument)
                .mapNotNull { enumEntryName(it) }
                .mapNotNull { Sides.fromName(it) }
                .fold(Sides.NONE) { acc, mask -> acc or mask }
        }
        return InsetsSet.fromTypeName(typeName, sides) ?: InsetsSet.EMPTY
    }

    private fun enumEntryName(expression: FirExpression?): String? =
        when (val expr = expression?.unwrapArgument()) {
            null -> null
            is FirEnumEntryDeserializedAccessExpression -> expr.enumEntryName.asString()
            is FirQualifiedAccessExpression -> expr.calleeReference.toResolvedEnumEntrySymbol()?.name?.asString()
            else -> null
        }

    private fun arrayElements(expression: FirExpression?): List<FirExpression> =
        when (val expr = expression?.unwrapArgument()) {
            null -> emptyList()
            is FirCollectionLiteral -> expr.arguments.map { it.unwrapArgument() }
            is FirVarargArgumentsExpression -> expr.arguments.map { it.unwrapArgument() }
            is FirFunctionCall -> expr.arguments.map { it.unwrapArgument() } // arrayOf(...)
            else -> emptyList()
        }

    /** Walks an element and unions everything that handles insets, including nested lambdas. */
    private inner class HandlingCollector : FirVisitorVoid() {
        var result: InsetsAnalysis = InsetsAnalysis.EMPTY
            private set

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            handledByCall(functionCall)?.let { result = result.union(it) }
            functionCall.acceptChildren(this)
        }
    }

    private fun handledByCall(call: FirFunctionCall): InsetsAnalysis? {
        val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return null
        val callableId = callee.callableId
        if (callableId.packageName == WindowInsetsNames.FOUNDATION_LAYOUT) {
            return handledByFoundationCall(call, callableId)
        }
        if (!isComposable(callee)) return null
        val insetsArgument = call.windowInsetsArgument()
        if (insetsArgument != null && callableId in WindowInsetsNames.KNOWN_LIBRARY_COMPOSABLES) {
            return evaluated(insetsArgument)
        }
        return handledInsets(callee)
    }

    private fun handledByFoundationCall(call: FirFunctionCall, callableId: CallableId): InsetsAnalysis? {
        val name = callableId.callableName
        WindowInsetsNames.SHORTHAND_PADDING_MODIFIERS[name]?.let { return InsetsAnalysis(it, unverifiable = false) }
        if (name in WindowInsetsNames.INSETS_CONSUMING_FUNCTIONS) {
            val insetsExpression = call.windowInsetsArgument() ?: call.explicitReceiver
            return evaluated(insetsExpression)
        }
        WindowInsetsNames.SINGLE_SIDE_FUNCTIONS[name]?.let { side ->
            val evaluated = evaluated(call.windowInsetsArgument() ?: call.explicitReceiver)
            return InsetsAnalysis(evaluated.handled.only(side), evaluated.unverifiable)
        }
        return null
    }

    private fun evaluated(expression: FirExpression?): InsetsAnalysis {
        val insets = WindowInsetsExpressionEvaluator.evaluateInsets(expression) ?: return InsetsAnalysis.UNKNOWN
        return InsetsAnalysis(insets, unverifiable = false)
    }

    /** The argument passed to a parameter of type `WindowInsets`, if any. */
    private fun FirFunctionCall.windowInsetsArgument(): FirExpression? {
        val mapping = resolvedArgumentMapping ?: return null
        return mapping.entries.firstOrNull { (_, parameter) ->
            parameter.returnTypeRef.coneType.classId == WindowInsetsNames.WINDOW_INSETS
        }?.key
    }
}

val FirSession.windowInsetsHandlingService: WindowInsetsHandlingService by FirSession.sessionComponentAccessor()

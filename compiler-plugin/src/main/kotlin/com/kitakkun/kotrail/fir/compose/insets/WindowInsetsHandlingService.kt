@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.compose.insets

import com.kitakkun.kotrail.compose.insets.InsetsAnalysis
import com.kitakkun.kotrail.compose.insets.InsetsSet
import com.kitakkun.kotrail.compose.insets.Sides
import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.inferred.InferredFactService
import com.kitakkun.kotrail.fir.inferred.InferredMetadata.arrayElements
import com.kitakkun.kotrail.fir.inferred.InferredMetadata.strings
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirEnumEntryDeserializedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedEnumEntrySymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.CallableId

/**
 * Per-session analysis of which window insets a composable handles. Shared by the FIR checkers
 * and, through the FIR declaration attached to each IR function, by the IR metadata writer.
 */
class WindowInsetsHandlingService(session: FirSession) : InferredFactService<FirNamedFunctionSymbol, InsetsAnalysis>(session) {
    override val rule: KotrailRule get() = KotrailRule.COMPOSE_WINDOW_INSETS
    override val annotation: ClassId get() = WindowInsetsNames.INFERRED_WINDOW_INSETS_HANDLING
    override val parameters: List<Name> get() = listOf(WindowInsetsNames.HANDLED_PARAM)
    override val empty: InsetsAnalysis get() = InsetsAnalysis.EMPTY

    /** A hand-written contract is what the writer would produce: it is read on a source declaration too. */
    override val metadataForSource: Boolean get() = true

    /**
     * Composables known to handle insets without being analyzed: the built-in entries for
     * Material 3, changed entry by entry by the project's `rules.compose.windowInsets.known`.
     */
    private val knowledgeBase: Map<String, InsetsSet> by lazy {
        val base = WindowInsetsNames.KNOWN_LIBRARY_COMPOSABLES
            .mapKeysTo(HashMap()) { (id, _) -> id.asSingleFqName().asString() }
        base.putAll(session.kotrailConfig.compose.knownInsetsHandlers)
        base
    }

    private fun known(callableId: CallableId): InsetsSet? = knowledgeBase[callableId.asSingleFqName().asString()]

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
    fun handledInsets(symbol: FirNamedFunctionSymbol): InsetsAnalysis = of(symbol)

    /** What an arbitrary expression (typically a `Modifier` chain) handles. */
    fun handledByExpression(expression: FirExpression): InsetsAnalysis {
        val collector = HandlingCollector()
        expression.accept(collector)
        return collector.result
    }

    override fun override(symbol: FirNamedFunctionSymbol): InsetsAnalysis? =
        declaredContract(symbol)?.let { InsetsAnalysis(it, unverifiable = false) }

    override fun knowledge(symbol: FirNamedFunctionSymbol): InsetsAnalysis? =
        known(symbol.callableId)?.let { InsetsAnalysis(it, unverifiable = false) }

    override fun decode(annotation: FirAnnotation): InsetsAnalysis =
        InsetsAnalysis(InsetsSet.decode(annotation.strings(WindowInsetsNames.HANDLED_PARAM)) ?: InsetsSet.EMPTY, unverifiable = false)

    override fun encode(value: InsetsAnalysis): List<List<String>>? =
        value.handled.takeUnless { it.isEmpty }?.let { listOf(it.encode()) }

    /** Only a composable without a declared contract is worth analyzing for the metadata. */
    override fun shouldWarm(symbol: FirNamedFunctionSymbol): Boolean = isComposable(symbol) && declaredContract(symbol) == null

    override fun analyze(symbol: FirNamedFunctionSymbol): InsetsAnalysis {
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
        if (insetsArgument != null && known(callableId) != null) {
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

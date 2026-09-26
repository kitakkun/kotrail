@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.compose.effects

import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirCollectionLiteral
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.name.Name

/**
 * Per-session analysis of which parameters a composable keeps for the life of an effect without
 * keeping them current: a lambda a caller passes for such a parameter is captured, so what that
 * lambda reads goes stale. For a source declaration the body is analyzed (see
 * [EffectCaptureAnalysis]), following calls into other composables transitively; for one on the
 * classpath the `@InferredEffectCapture` metadata the plugin wrote is read, and a library without
 * it is taken as keeping its lambdas current. Bodies are released after Fir2Ir, so the checker
 * warms the cache for every composable it looks into; the IR writer only reads cached results.
 */
class EffectCaptureService(session: FirSession) : FirExtensionSessionComponent(session) {
    private val cache = HashMap<FirNamedFunctionSymbol, Set<String>>()
    private val visiting = HashSet<FirNamedFunctionSymbol>()

    /** Names of the parameters of [symbol] that a long-lived effect in its body captures raw; empty when none or unknown. */
    fun capturedParameters(symbol: FirNamedFunctionSymbol): Set<String> {
        cache[symbol]?.let { return it }
        if (!visiting.add(symbol)) return emptySet()
        try {
            val result = compute(symbol)
            cache[symbol] = result
            return result
        } finally {
            visiting.remove(symbol)
        }
    }

    private fun compute(symbol: FirNamedFunctionSymbol): Set<String> {
        if (!symbol.origin.fromSource) return inferredMetadata(symbol)
        if (!symbol.isComposable(session)) return emptySet()
        symbol.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
        val function = symbol.fir
        if (function.body == null) return emptySet()
        return EffectCaptureAnalysis.capturedParameters(session, session.kotrailConfig.compose.rememberKeysFunctions, function, ::capturedParameters)
    }

    private fun inferredMetadata(symbol: FirNamedFunctionSymbol): Set<String> {
        val annotation = symbol.resolvedAnnotationsWithArguments
            .firstOrNull { it.toAnnotationClassId(session) == ComposeNames.INFERRED_EFFECT_CAPTURE }
            ?: return emptySet()
        val argument = annotation.findArgumentByName(CAPTURED_PARAM, returnFirstWhenNotFound = false)
        return arrayElements(argument).mapNotNullTo(LinkedHashSet()) { (it as? FirLiteralExpression)?.value as? String }
    }

    private fun arrayElements(expression: FirExpression?): List<FirExpression> =
        when (val expr = expression?.unwrapArgument()) {
            null -> emptyList()
            is FirCollectionLiteral -> expr.arguments.map { it.unwrapArgument() }
            is FirVarargArgumentsExpression -> expr.arguments.map { it.unwrapArgument() }
            is FirFunctionCall -> expr.arguments.map { it.unwrapArgument() } // arrayOf(...)
            else -> emptyList()
        }

    private companion object {
        val CAPTURED_PARAM = Name.identifier("captured")
    }
}

val FirSession.effectCaptureService: EffectCaptureService by FirSession.sessionComponentAccessor()

@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.compose.effects

import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.inferred.InferredFactService
import com.kitakkun.kotrail.fir.inferred.InferredMetadata.strings
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
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
class EffectCaptureService(session: FirSession) : InferredFactService<FirNamedFunctionSymbol, Set<String>>(session) {
    override val rule: KotrailRule get() = KotrailRule.COMPOSE_REMEMBER_KEYS
    override val annotation: ClassId get() = ComposeNames.INFERRED_EFFECT_CAPTURE
    override val parameters: List<Name> get() = listOf(CAPTURED_PARAM)
    override val empty: Set<String> get() = emptySet()

    /** Names of the parameters of [symbol] that a long-lived effect in its body captures raw; empty when none or unknown. */
    fun capturedParameters(symbol: FirNamedFunctionSymbol): Set<String> = of(symbol)

    override fun shouldWarm(symbol: FirNamedFunctionSymbol): Boolean = symbol.isComposable(session)

    override fun decode(annotation: FirAnnotation): Set<String> = annotation.strings(CAPTURED_PARAM).toCollection(LinkedHashSet())

    override fun encode(value: Set<String>): List<List<String>>? = value.takeIf { it.isNotEmpty() }?.let { listOf(it.toList()) }

    override fun analyze(symbol: FirNamedFunctionSymbol): Set<String> {
        if (!symbol.isComposable(session)) return emptySet()
        val function = symbol.fir
        if (function.body == null) return emptySet()
        return EffectCaptureAnalysis.capturedParameters(session, session.kotrailConfig.compose.rememberKeysFunctions, function, ::capturedParameters)
    }

    private companion object {
        val CAPTURED_PARAM = Name.identifier("captured")
    }
}

val FirSession.effectCaptureService: EffectCaptureService by FirSession.sessionComponentAccessor()

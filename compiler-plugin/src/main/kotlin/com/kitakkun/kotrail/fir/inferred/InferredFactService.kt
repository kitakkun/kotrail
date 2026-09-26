@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.inferred

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.inferred.InferredMetadata.annotationOf
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * A fact the plugin infers about a declaration, memoized per symbol, that crosses modules as an
 * annotation of string arrays: what a composable handles, reads or captures, what a function
 * starts or allocates, what it requires of its arguments.
 *
 * [of] answers for any symbol, source or classpath, in this order: [override] (a hand-written
 * contract, a knowledge base that wins over everything); the declaration's own [annotation],
 * always for a classpath symbol and also for a source one when [metadataForSource]; [knowledge]
 * (a knowledge base that fills in for library code); then, for a source symbol only, [analyze]
 * on the body resolved to `BODY_RESOLVE`. Bodies are released after Fir2Ir, so
 * [com.kitakkun.kotrail.fir.inferred.InferredFactWarmup] computes the fact for every source
 * declaration while FIR is available and the IR writer only reads what was cached.
 *
 * [encode] is the writer's side: the arrays to put in the annotation, one per [parameters], or
 * null when the fact says nothing worth writing.
 */
abstract class InferredFactService<S : FirBasedSymbol<*>, T>(session: FirSession) : FirExtensionSessionComponent(session) {
    /** The rule whose being on switches the analysis and the metadata on. */
    abstract val rule: KotrailRule

    /** The annotation the fact travels as. */
    abstract val annotation: ClassId

    /** Its parameters, each an array of strings, in declaration order. */
    abstract val parameters: List<Name>

    /** The fact for a declaration that says nothing, and for a symbol reached from its own analysis. */
    protected abstract val empty: T

    /** Whether a source declaration's own [annotation] is read before its body (a hand-written contract that the writer also produces). */
    protected open val metadataForSource: Boolean = false

    private val cache by lazy { SymbolCache<S, T>(empty) }

    /** The fact for [symbol], memoized. */
    fun of(symbol: S): T = cache.getOrCompute(symbol) { compute(symbol) }

    /** The fact as cached, or null when [symbol] was never asked about. */
    fun cached(symbol: S): T? = cache.cached(symbol)

    /** The fact read from the declaration's own [annotation], or null when it has none. */
    fun declared(symbol: S): T? = symbol.annotationOf(session, annotation)?.let { decode(it) }

    /** Computes the fact for a source declaration, so that the IR writer finds it cached; a no-op for anything else. */
    fun warm(symbol: S) {
        if (symbol.origin.fromSource && shouldWarm(symbol)) of(symbol)
    }

    /** Whether a source declaration is worth analyzing for the metadata at all (a composable, a non-suspend function). */
    protected open fun shouldWarm(symbol: S): Boolean = true

    /** What wins over everything, or null. */
    protected open fun override(symbol: S): T? = null

    /** What fills in for a declaration without metadata, or null: a knowledge base. */
    protected open fun knowledge(symbol: S): T? = null

    /** The fact read from [annotation]'s arrays. */
    protected abstract fun decode(annotation: FirAnnotation): T

    /** The fact from the body of a source declaration, resolved to `BODY_RESOLVE`; [empty] when it has no body. */
    protected abstract fun analyze(symbol: S): T

    /** The arrays to write for [value], one per [parameters], or null when there is nothing to write. */
    abstract fun encode(value: T): List<List<String>>?

    private fun compute(symbol: S): T {
        override(symbol)?.let { return it }
        if (!symbol.origin.fromSource || metadataForSource) declared(symbol)?.let { return it }
        knowledge(symbol)?.let { return it }
        if (!symbol.origin.fromSource) return empty
        symbol.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
        return analyze(symbol)
    }
}

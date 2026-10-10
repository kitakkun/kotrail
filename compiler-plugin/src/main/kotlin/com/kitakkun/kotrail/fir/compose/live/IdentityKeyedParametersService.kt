@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.compose.live

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.inferred.FactArguments
import com.kitakkun.kotrail.fir.inferred.InferredFactService
import com.kitakkun.kotrail.inferred.InferredFactNames
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Per-session analysis of which parameters a composable remembers a copy of under their identity:
 * `remember(items) { items.filter { ... } }`, where `items` is a parameter (or a local `val`
 * initialized with one), or a parameter handed as it is to a composable that does so,
 * transitively. A caller that passes a value which changes in place for such a parameter (a
 * `SnapshotStateList`, a `State`) gets a copy that goes stale, since the key stays the same
 * object. For a source declaration the body is analyzed (see [LiveValueAnalysis]); for one on the
 * classpath the `identityKeyedParameters` fact the plugin wrote is read, and a library without it
 * is taken as remembering nothing.
 *
 * Each parameter maps to the composable that holds the `remember`, null for the composable itself.
 * The fact writes a parameter it hands on as `name@Holder` (`transactions@TrafficTab`) and one it
 * remembers itself as the bare name, so that an entry written before holders were recorded reads
 * as it did: the composable itself.
 */
class IdentityKeyedParametersService(session: FirSession) : InferredFactService<FirNamedFunctionSymbol, Map<String, String?>>(session) {
    override val rules: List<KotrailRule> get() = listOf(KotrailRule.COMPOSE_LIVE_VALUE_REMEMBERED)
    override val annotation: ClassId get() = ComposeNames.INFERRED_IDENTITY_KEYED_PARAMETERS
    override val factName: String get() = InferredFactNames.IDENTITY_KEYED_PARAMETERS
    override val parameters: List<Name> get() = listOf(NAMES)
    override val empty: Map<String, String?> get() = emptyMap()

    /**
     * The parameters of [symbol] that key a remembered copy by identity, each with the composable
     * that holds the `remember` (null: [symbol] itself); empty when none or unknown.
     */
    fun identityKeyedParameters(symbol: FirNamedFunctionSymbol): Map<String, String?> = of(symbol)

    override fun shouldWarm(symbol: FirNamedFunctionSymbol): Boolean = symbol.isComposable(session)

    override fun decode(arguments: FactArguments): Map<String, String?> =
        arguments.strings(NAMES).associateTo(LinkedHashMap()) { entry ->
            val at = entry.indexOf(HOLDER_SEPARATOR)
            if (at < 0) entry to null else entry.substring(0, at) to entry.substring(at + 1)
        }

    override fun encode(value: Map<String, String?>): List<List<String>>? =
        value.takeIf { it.isNotEmpty() }?.let { keyed -> listOf(keyed.map { (name, holder) -> if (holder == null) name else "$name$HOLDER_SEPARATOR$holder" }) }

    override fun analyze(symbol: FirNamedFunctionSymbol): Map<String, String?> {
        if (!symbol.isComposable(session)) return emptyMap()
        return LiveValueAnalysis.identityKeyedParameters(session, symbol.fir, ::identityKeyedParameters)
    }

    private companion object {
        val NAMES = Name.identifier("names")

        /** Between a parameter's name and its holder's in an entry of [NAMES]; a name written without backticks never contains it. */
        const val HOLDER_SEPARATOR = '@'
    }
}

val FirSession.identityKeyedParametersService: IdentityKeyedParametersService by FirSession.sessionComponentAccessor()

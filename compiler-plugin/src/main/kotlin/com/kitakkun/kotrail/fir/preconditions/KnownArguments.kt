@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.preconditions

import com.kitakkun.kotrail.preconditions.Value
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol

/**
 * The compile-time value of every parameter of [callee] that this call fixes, by parameter name:
 * each argument folded with [CondConverter], then, when [callee] is compiled in this module, the
 * default of each parameter left out, which may refer to earlier parameters. A vararg parameter,
 * and an argument or default that does not fold, are left out.
 */
fun FirFunctionCall.knownArgumentValues(session: FirSession, callee: FirFunctionSymbol<*>): Map<String, Value> {
    val mapping = resolvedArgumentMapping ?: return emptyMap()
    val values = HashMap<String, Value>()
    val argumentConverter = CondConverter(session, emptyMap())
    val passed = HashSet<String>()
    for ((argument, parameter) in mapping) {
        val name = parameter.name.asString()
        passed += name
        if (parameter.isVararg) continue
        argumentConverter.convert(argument)?.evaluate(emptyMap())?.let { values[name] = it }
    }
    if (callee.origin.fromSource) {
        val calleeParams = callee.valueParameterSymbols.associate { it as FirBasedSymbol<*> to it.name.asString() }
        val defaultConverter = CondConverter(session, calleeParams)
        for (parameter in callee.valueParameterSymbols) {
            val name = parameter.name.asString()
            if (name in passed) continue
            val default = parameter.fir.defaultValue ?: continue
            defaultConverter.convert(default)?.evaluate(values)?.let { values[name] = it }
        }
    }
    return values
}

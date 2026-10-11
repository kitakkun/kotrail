package com.kitakkun.kotrail.fir

import com.kitakkun.kotrail.exclude.Glob
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.unwrapFakeOverrides

/**
 * Whether one of [globs] matches the callable's fully qualified name (`kotlin.comparisons.maxOf`,
 * `com.acme.Vec.Companion.of`) or, for a member reached through a subtype, the name of the member
 * it copies.
 */
fun FirCallableSymbol<*>.fqNameMatchesAny(globs: List<Glob>): Boolean {
    if (globs.isEmpty()) return false
    val names = listOfNotNull(callableId, unwrapFakeOverrides().callableId).map { it.asSingleFqName().asString() }
    return names.any { name -> globs.any { it.matches(name) } }
}

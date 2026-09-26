package com.kitakkun.kotrail.fir.inferred

/**
 * Memoizes a per-symbol analysis that may reach itself through the calls it follows: a symbol
 * asked about again while its own analysis runs gets [onCycle], so recursion ends where it
 * started. A null result is a result and is cached like any other.
 */
class SymbolCache<S : Any, T>(private val onCycle: T) {
    private val cache = HashMap<S, T>()
    private val visiting = HashSet<S>()

    /** The cached result, or null when [symbol] was never computed (or its result is null). */
    fun cached(symbol: S): T? = cache[symbol]

    operator fun contains(symbol: S): Boolean = cache.containsKey(symbol)

    fun getOrCompute(symbol: S, compute: () -> T): T {
        if (cache.containsKey(symbol)) @Suppress("UNCHECKED_CAST") return cache[symbol] as T
        if (!visiting.add(symbol)) return onCycle
        try {
            val result = compute()
            cache[symbol] = result
            return result
        } finally {
            visiting.remove(symbol)
        }
    }
}

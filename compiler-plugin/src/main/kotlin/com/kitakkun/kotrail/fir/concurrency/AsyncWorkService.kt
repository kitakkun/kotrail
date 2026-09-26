@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.concurrency

import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Per-session analysis of which non-suspending functions start asynchronous work without
 * handing their caller a way to wait for it: the body calls a starter (`launch`, `async`,
 * `Thread.start`, a posted runnable; `delayForCompletion.starters`) as a statement and drops
 * the result, or calls another function that does so. A function that returns the starter's
 * result, or assigns it, gives its caller a handle and is not counted; a suspending function
 * never is, since a caller can already await it.
 *
 * For a source declaration the body is analyzed; for one on the classpath the
 * `@InferredStartsAsyncWork` metadata the plugin wrote is read, and a library without it is
 * taken as not starting work. Results are memoized per symbol; the fire-and-forget checker warms
 * the cache for every named function it sees, so that the IR writer only reads cached results.
 */
class AsyncWorkService(session: FirSession) : FirExtensionSessionComponent(session) {
    private val cache = HashMap<FirNamedFunctionSymbol, Boolean>()
    private val visiting = HashSet<FirNamedFunctionSymbol>()

    /** Whether [symbol] starts asynchronous work its caller cannot wait for. */
    fun startsAsyncWork(symbol: FirNamedFunctionSymbol): Boolean {
        cache[symbol]?.let { return it }
        if (!visiting.add(symbol)) return false
        try {
            val result = compute(symbol)
            cache[symbol] = result
            return result
        } finally {
            visiting.remove(symbol)
        }
    }

    /**
     * The starter a source function's body calls as a statement and discards (`launch`), or null.
     * Only the body itself, not the functions it calls: what the fire-and-forget rule reports.
     */
    fun discardedStarter(symbol: FirNamedFunctionSymbol): String? {
        if (!symbol.origin.fromSource || symbol.isSuspend) return null
        symbol.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
        val body = symbol.fir.body ?: return null
        return firstStartingStatement(body, transitive = false)?.second
    }

    /**
     * What [statement] starts, at statement level (through `if`, `when`, `try` and nested blocks,
     * but not through lambdas): the name of a discarded starter call, or of a called function that
     * starts work; null when nothing does.
     */
    fun startingCallee(statement: FirStatement): String? = firstStartingStatement(statement, transitive = true)?.second

    private fun compute(symbol: FirNamedFunctionSymbol): Boolean {
        if (symbol.isSuspend) return false
        if (!symbol.origin.fromSource) return symbol.hasAnnotation(INFERRED_STARTS_ASYNC_WORK, session)
        symbol.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
        val body = symbol.fir.body ?: return false
        return firstStartingStatement(body, transitive = true) != null
    }

    /** The first statement under [root] that starts work, with the name of what it calls. */
    private fun firstStartingStatement(root: FirElement, transitive: Boolean): Pair<FirStatement, String>? {
        val starters = session.kotrailConfig.asyncWork.starters
        var found: Pair<FirStatement, String>? = null

        fun visitStatement(statement: FirStatement) {
            if (found != null) return
            when (statement) {
                is FirFunctionCall -> {
                    val callee = statement.calleeReference.toResolvedCallableSymbol() ?: return
                    val name = callee.fqName()
                    if (name != null && name in starters) {
                        found = statement to callee.name.asString()
                    } else if (transitive && callee is FirNamedFunctionSymbol && startsAsyncWork(callee)) {
                        found = statement to callee.name.asString()
                    }
                }
                is FirBlock -> statement.statements.forEach(::visitStatement)
                is FirWhenExpression -> statement.branches.forEach { visitStatement(it.result) }
                is FirTryExpression -> {
                    visitStatement(statement.tryBlock)
                    statement.catches.forEach { visitStatement(it.block) }
                    statement.finallyBlock?.let(::visitStatement)
                }
                is FirAnonymousFunction -> {}
                else -> {}
            }
        }

        when (root) {
            is FirStatement -> visitStatement(root)
            else -> {}
        }
        return found
    }

    private fun FirCallableSymbol<*>.fqName(): String? = callableId?.asSingleFqName()?.asString()

    companion object {
        val INFERRED_STARTS_ASYNC_WORK = ClassId(FqName("com.kitakkun.kotrail.concurrency"), Name.identifier("InferredStartsAsyncWork"))
    }
}

val FirSession.asyncWorkService: AsyncWorkService by FirSession.sessionComponentAccessor()

@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.errors

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.exclude.Glob
import com.kitakkun.kotrail.fir.inferred.FactArguments
import com.kitakkun.kotrail.fir.inferred.InferredFactService
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.inferred.InferredFactNames
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirCatch
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirThrowExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Per-session analysis of which functions record a failure before rethrowing it: the whole body
 * is one `try` (a block body of that single statement, or an expression body), and its clause
 * for `Throwable` or `Exception` hands the exception whole to whoever records it
 * (`failure.value = e`, `report(e)`; the forwarding test of the catch-too-broad rule) and then
 * rethrows it (`throw e`, `throw Wrapped(e)`):
 *
 * ```kotlin
 * fun render(canvas: Canvas) {
 *     try {
 *         draw(canvas)
 *     } catch (e: Throwable) {
 *         failure.value = e
 *         throw e
 *     }
 * }
 * ```
 *
 * A caller's broad catch around a call to such a function has nothing left to hand on, and the
 * catch-too-broad rule accepts it empty. The function's other clauses must not let anything else
 * out unrecorded: each catches `CancellationException`, records and rethrows the same way, or
 * throws nothing at all. A clause that only rethrows (`catch (e: Throwable) { throw e }`)
 * records nothing and does not count.
 *
 * For a source declaration the body is analyzed; for one on the classpath the `recordsFailure`
 * entry of a dependency's facts file or the `@InferredRecordsFailure` annotation is read, and a
 * library without either is taken as not recording.
 */
class RecordsFailureService(session: FirSession) : InferredFactService<FirNamedFunctionSymbol, Boolean>(session) {
    override val rules: List<KotrailRule> get() = listOf(KotrailRule.CATCH_TOO_BROAD)
    override val annotation: ClassId get() = INFERRED_RECORDS_FAILURE
    override val factName: String get() = InferredFactNames.RECORDS_FAILURE
    override val parameters: List<Name> get() = emptyList()
    override val empty: Boolean get() = false

    /** Whether [symbol] records every failure of its body before rethrowing it. */
    fun recordsFailure(symbol: FirNamedFunctionSymbol): Boolean = of(symbol)

    /** A marker: its presence is the fact. */
    override fun decode(arguments: FactArguments): Boolean = true

    override fun encode(value: Boolean): List<List<String>>? = if (value) emptyList() else null

    override fun analyze(symbol: FirNamedFunctionSymbol): Boolean {
        val statement = symbol.fir.body?.statements?.singleOrNull() ?: return false
        val tryExpression = ((statement as? FirReturnExpression)?.result ?: statement) as? FirTryExpression ?: return false
        val loggers = session.kotrailConfig.catchTooBroad.loggers.map(::Glob)
        var recorded = false
        for (catch in tryExpression.catches) {
            when {
                catch.recordsAndRethrows(loggers) -> if (catch.caughtTypeNames(session).any { it in RECORDED_TYPES }) recorded = true
                catch.caughtTypeNames(session).any { it in CANCELLATION_TYPES } -> {}
                catch.throwsAnything() -> return false
            }
        }
        return recorded
    }

    /** Forwards the exception whole in every statement, records it in at least one, and ends by throwing it. */
    private fun FirCatch.recordsAndRethrows(loggers: List<Glob>): Boolean =
        block.statements.size >= 2 && block.statements.last() is FirThrowExpression && forwards(loggers)

    private fun FirCatch.throwsAnything(): Boolean {
        var found = false
        block.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (found) return
                if (element is FirThrowExpression) {
                    found = true
                    return
                }
                element.acceptChildren(this)
            }
        })
        return found
    }

    companion object {
        val INFERRED_RECORDS_FAILURE = ClassId(FqName("com.kitakkun.kotrail.errors"), Name.identifier("InferredRecordsFailure"))

        /** The types a recording clause catches, and a caller's empty clause may catch around it. */
        val RECORDED_TYPES: Set<String> = setOf("kotlin.Throwable", "kotlin.Exception", "java.lang.Throwable", "java.lang.Exception")

        private val CANCELLATION_TYPES = setOf(
            "kotlinx.coroutines.CancellationException",
            "kotlin.coroutines.cancellation.CancellationException",
            "java.util.concurrent.CancellationException",
        )
    }
}

val FirSession.recordsFailureService: RecordsFailureService by FirSession.sessionComponentAccessor()

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirTryExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirCatch
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirThrowExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.types.abbreviatedTypeOrSelf
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Keeps catch clauses to the failures the code can recover from:
 *
 * ```kotlin
 * try { repository.save(item) } catch (e: Exception) { showError() }    // reported
 * try { repository.save(item) } catch (e: IOException) { showError() }  // fine
 * try { work() } catch (e: Throwable) { cleanup(); throw e }            // fine: rethrown
 * ```
 *
 * `catch (e: Exception)` handles every failure alike: the network error it was written for,
 * the `NullPointerException` that is a bug, the `CancellationException` that a coroutine needs
 * to see. Naming the exceptions the code recovers from keeps the rest visible. A clause that
 * rethrows its parameter, or ends in a `throw` of anything (a wrapped rethrow), is a cleanup or
 * translation and is left alone. The types are `catchTooBroad.types`, matched exactly. A
 * project's boundaries (an error handler at the top of a request, a plugin host) opt out with
 * the rule's `exclude` predicate.
 */
object CatchTooBroadChecker : FirTryExpressionChecker(MppCheckerKind.Common) {
    private val CANCELLATION_TYPES = setOf(
        "kotlinx.coroutines.CancellationException",
        "kotlin.coroutines.cancellation.CancellationException",
        "java.util.concurrent.CancellationException",
    )

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirTryExpression) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.CATCH_TOO_BROAD)) return
        val session = context.session
        val broad = config.catchTooBroad.types
        for ((index, catch) in expression.catches.withIndex()) {
            val parameter = catch.parameter
            val source = parameter.source ?: continue
            if (source.kind is KtFakeSourceElementKind) continue
            // `kotlin.Exception` is a type alias of `java.lang.Exception` on the JVM: the name as written and the class it expands to both count.
            val written = parameter.returnTypeRef.coneType
            val classId = written.classId ?: continue
            val names = listOfNotNull(classId, written.fullyExpandedType().classId, written.abbreviatedTypeOrSelf.classId).map { it.asSingleFqName().asString() }
            if (names.none { it in broad }) continue
            if (catch.rethrows()) continue
            // A clause before it that rethrows cancellation has already let cancellation through.
            val cancellationHandled = expression.catches.take(index).any { earlier ->
                earlier.parameter.returnTypeRef.coneType.let { type ->
                    listOfNotNull(type.classId, type.fullyExpandedType().classId, type.abbreviatedTypeOrSelf.classId)
                        .any { it.asSingleFqName().asString() in CANCELLATION_TYPES }
                } && earlier.rethrows()
            }
            val swallows = if (cancellationHandled) "bugs included" else "bugs and cancellations included"
            reportKotrail(source, KotrailDiagnostics.CATCH_TOO_BROAD, classId.shortClassName.asString(), swallows)
        }
    }

    /** Whether the clause throws its parameter anywhere, or ends in a throw of anything. */
    private fun FirCatch.rethrows(): Boolean {
        if (block.statements.lastOrNull() is FirThrowExpression) return true
        val target = parameter.symbol
        var found = false
        block.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (found) return
                element.acceptChildren(this)
            }

            override fun visitThrowExpression(throwExpression: FirThrowExpression) {
                val thrown = (throwExpression.exception as? FirSmartCastExpression)?.originalExpression ?: throwExpression.exception
                if ((thrown as? FirQualifiedAccessExpression)?.calleeReference?.toResolvedCallableSymbol() == target) found = true
                throwExpression.acceptChildren(this)
            }
        })
        return found
    }
}

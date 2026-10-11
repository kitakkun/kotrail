package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.errors.caughtTypeNames
import com.kitakkun.kotrail.fir.errors.failureBoundary
import com.kitakkun.kotrail.fir.errors.forwards
import com.kitakkun.kotrail.fir.errors.rethrows
import com.kitakkun.kotrail.exclude.Glob
import com.kitakkun.kotrail.fir.errors.RecordsFailureService
import com.kitakkun.kotrail.fir.errors.recordsFailureService
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirTryExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirCatch
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
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
 * try { render() } catch (e: Throwable) { failure.value = e }          // fine: forwarded whole
 * try { scene.render() } catch (_: Throwable) { }                       // fine when render() records, then rethrows
 * ```
 *
 * `catch (e: Exception)` handles every failure alike: the network error it was written for,
 * the `NullPointerException` that is a bug, the `CancellationException` that a coroutine needs
 * to see. Naming the exceptions the code recovers from keeps the rest visible. A clause that
 * rethrows its parameter, or ends in a `throw` of anything (a wrapped rethrow), is a cleanup or
 * translation and is left alone. So is a forwarding clause, where every statement hands the
 * parameter whole to a call, an assignment or a throw and nothing inspects it: an error
 * boundary that passes the failure to whoever records it. An empty `Throwable` or `Exception` clause
 * around a single call to a function that has already recorded the failure before rethrowing it (see
 * [RecordsFailureService]) is left alone too, in a coroutine only after a clause that rethrows
 * cancellation. The types are `catchTooBroad.types`, matched exactly.
 *
 * Narrow inside, broad at boundaries: at a failure boundary ([failureBoundary]: the top of a
 * launched coroutine, a listener, a thread) a broad clause is what keeps an unexpected failure
 * from ending the work silently, so there a clause that reads its parameter (logs it, reports
 * it) is quiet; one that never reads it is still reported, since the failure vanishes. The
 * boundaries are those of the `unguardedBoundary` settings, read whether or not that rule is on.
 * Other boundaries (an error handler at the top of a request, a plugin host) opt out with the
 * rule's `exclude` predicate.
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
        val boundary by lazy(LazyThreadSafetyMode.NONE) { failureBoundary(config.failureBoundaries) }
        for ((index, catch) in expression.catches.withIndex()) {
            val parameter = catch.parameter
            val source = parameter.source ?: continue
            if (source.kind is KtFakeSourceElementKind) continue
            val classId = parameter.returnTypeRef.coneType.classId ?: continue
            val names = catch.caughtTypeNames(session)
            if (names.none { it in broad }) continue
            // `catch (@Suppress("KOTRAIL_CATCH_TOO_BROAD") e: Exception)` names exactly this clause; the compiler's own
            // suppression only sees the enclosing declarations and expressions.
            if (catch.parameterSuppresses(session)) continue
            if (catch.rethrows()) continue
            val loggers = config.catchTooBroad.loggers.map(::Glob)
            if (catch.forwards(loggers)) continue
            // A clause before it that rethrows cancellation has already let cancellation through.
            val cancellationHandled = expression.catches.take(index).any { earlier ->
                earlier.caughtTypeNames(session).any { it in CANCELLATION_TYPES } && earlier.rethrows()
            }
            if (catch.block.statements.isEmpty() && names.any { it in RecordsFailureService.RECORDED_TYPES } &&
                expression.callsRecordingFunction(session) && (cancellationHandled || !context.isSuspendContext(session))
            ) continue
            val swallowed = catch.swallows(loggers)
            if (config.catchTooBroad.report == "swallowed" && !swallowed) continue
            val root = boundary
            if (root != null && catch.readsParameter()) continue
            val included = if (cancellationHandled || !context.isSuspendContext(session)) "bugs included" else "bugs and cancellations included"
            val rest = when {
                root != null ->
                    "$included, and the failure goes no further than this clause. This is a failure boundary ('${root.description}'): " +
                        "log or report the exception here, or it vanishes."
                swallowed ->
                    "$included, and the failure goes no further than this clause. Let it out as a result, an error state or a " +
                        "rethrow, or catch what this code can recover from."
                else -> "$included. Catch the exceptions this code can recover from, or rethrow what it cannot."
            }
            reportKotrail(source, KotrailDiagnostics.CATCH_TOO_BROAD, classId.shortClassName.asString(), rest)
        }
    }

    /**
     * Whether the try body is a single call (or a call chain, by its outermost call, `scene?.render(canvas)` included)
     * to a function that records every failure before rethrowing it.
     */
    private fun FirTryExpression.callsRecordingFunction(session: FirSession): Boolean {
        val statement = tryBlock.statements.singleOrNull() ?: return false
        val call = ((statement as? FirSafeCallExpression)?.selector ?: statement) as? FirFunctionCall ?: return false
        val callee = call.calleeReference.toResolvedCallableSymbol() as? FirNamedFunctionSymbol ?: return false
        return session.recordsFailureService.recordsFailure(callee)
    }

    private fun FirCatch.parameterSuppresses(session: FirSession): Boolean {
        val suppress = parameter.symbol.resolvedAnnotationsWithArguments
            .firstOrNull { it.toAnnotationClassId(session) == StandardClassIds.Annotations.Suppress } ?: return false
        val argument = suppress.findArgumentByName(Name.identifier("names"), returnFirstWhenNotFound = true) ?: return false
        val elements = (argument as? FirVarargArgumentsExpression)?.arguments ?: listOf(argument)
        return elements.any { element ->
            val value = (element.unwrapArgument() as? FirLiteralExpression)?.value as? String
            value != null && value.startsWith(KotrailDiagnostics.CATCH_TOO_BROAD.baseName)
        }
    }

    /**
     * Whether the failure goes no further than the clause: the parameter is never read, or every
     * read of it (as a receiver or an argument, `e.message` included) ends up in a logging call
     * and nowhere else. A read that reaches a return, an assignment, a throw, or a call outside
     * the logger list hands the failure on.
     */
    private fun FirCatch.swallows(loggers: List<Glob>): Boolean {
        val target = parameter.symbol
        var handedOn = false
        val ancestors = ArrayDeque<FirElement>()
        block.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (handedOn) return
                ancestors.addLast(element)
                element.acceptChildren(this)
                ancestors.removeLast()
            }

            override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
                if (propertyAccessExpression.calleeReference.toResolvedCallableSymbol() == target) {
                    // The innermost call the read feeds, through receivers and member accesses such as e.message.
                    val call = ancestors.asReversed().firstOrNull { it is FirFunctionCall && it !is FirPropertyAccessExpression } as? FirFunctionCall
                    val callee = call?.calleeReference?.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString()
                    val logged = callee != null && loggers.any { it.matches(callee) }
                    if (!logged) handedOn = true
                }
                visitElement(propertyAccessExpression)
            }
        })
        return !handedOn
    }

    /** Whether the clause reads its parameter anywhere: logs it, reports it, inspects it. */
    private fun FirCatch.readsParameter(): Boolean {
        val target = parameter.symbol
        var found = false
        block.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (found) return
                if (element is FirQualifiedAccessExpression && element.calleeReference.toResolvedCallableSymbol() == target) {
                    found = true
                    return
                }
                element.acceptChildren(this)
            }
        })
        return found
    }
}

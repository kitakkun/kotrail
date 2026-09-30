package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.exclude.Glob
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirTryExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirCatch
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirThrowExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.name.Name
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
 * try { render() } catch (e: Throwable) { failure.value = e }          // fine: forwarded whole
 * ```
 *
 * `catch (e: Exception)` handles every failure alike: the network error it was written for,
 * the `NullPointerException` that is a bug, the `CancellationException` that a coroutine needs
 * to see. Naming the exceptions the code recovers from keeps the rest visible. A clause that
 * rethrows its parameter, or ends in a `throw` of anything (a wrapped rethrow), is a cleanup or
 * translation and is left alone. So is a forwarding clause, where every statement hands the
 * parameter whole to a call, an assignment or a throw and nothing inspects it: an error
 * boundary that passes the failure to whoever records it. The types are `catchTooBroad.types`, matched exactly. A
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
            // `catch (@Suppress("KOTRAIL_CATCH_TOO_BROAD") e: Exception)` names exactly this clause; the compiler's own
            // suppression only sees the enclosing declarations and expressions.
            if (catch.parameterSuppresses(session)) continue
            if (catch.rethrows()) continue
            val loggers = config.catchTooBroad.loggers.map(::Glob)
            if (catch.forwards(loggers)) continue
            // A clause before it that rethrows cancellation has already let cancellation through.
            val cancellationHandled = expression.catches.take(index).any { earlier ->
                earlier.parameter.returnTypeRef.coneType.let { type ->
                    listOfNotNull(type.classId, type.fullyExpandedType().classId, type.abbreviatedTypeOrSelf.classId)
                        .any { it.asSingleFqName().asString() in CANCELLATION_TYPES }
                } && earlier.rethrows()
            }
            val swallowed = catch.swallows(loggers)
            if (config.catchTooBroad.report == "swallowed" && !swallowed) continue
            val included = if (cancellationHandled) "bugs included" else "bugs and cancellations included"
            val rest = if (swallowed) {
                "$included, and the failure goes no further than this clause. Let it out as a result, an error state or a " +
                    "rethrow, or catch what this code can recover from."
            } else {
                "$included. Catch the exceptions this code can recover from, or rethrow what it cannot."
            }
            reportKotrail(source, KotrailDiagnostics.CATCH_TOO_BROAD, classId.shortClassName.asString(), rest)
        }
    }

    private fun FirCatch.parameterSuppresses(session: org.jetbrains.kotlin.fir.FirSession): Boolean {
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

    /**
     * Whether the clause only forwards its parameter: every statement passes it whole as an
     * argument of a call that is not a logger (`onError(e)`), assigns it whole (`state.failure = e`),
     * or throws it, bare or as a constructor argument (`throw e`, `throw Wrapped(e)`). No other read
     * of it is allowed anywhere in the clause: no `is` check, no `when (e)`, no `e.message`.
     */
    private fun FirCatch.forwards(loggers: List<Glob>): Boolean {
        val statements = block.statements
        if (statements.isEmpty()) return false
        val target = parameter.symbol
        fun FirExpression.isParameter(): Boolean {
            val expression = (this as? FirSmartCastExpression)?.originalExpression ?: this
            return expression is FirQualifiedAccessExpression && expression.calleeReference.toResolvedCallableSymbol() == target
        }
        // How many arguments of the call are the parameter itself; none for a logger, which swallows it.
        fun FirFunctionCall.forwardedArguments(): Int {
            val callee = calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString()
            if (callee != null && loggers.any { it.matches(callee) }) return 0
            return argumentList.arguments.count { it.unwrapArgument().isParameter() }
        }
        var forwardedReads = 0
        for (statement in statements) {
            val forwarded = when (statement) {
                is FirVariableAssignment -> if (statement.rValue.isParameter()) 1 else 0
                is FirThrowExpression -> when {
                    statement.exception.isParameter() -> 1
                    else -> (statement.exception as? FirFunctionCall)?.forwardedArguments() ?: 0
                }
                is FirFunctionCall -> statement.forwardedArguments()
                else -> 0
            }
            if (forwarded == 0) return false
            forwardedReads += forwarded
        }
        // Every read of the parameter is one of the forwarded ones: nothing else looks inside it.
        var reads = 0
        block.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element is FirQualifiedAccessExpression && element.calleeReference.toResolvedCallableSymbol() == target) reads++
                element.acceptChildren(this)
            }
        })
        return reads == forwardedReads
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

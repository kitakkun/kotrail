package com.kitakkun.kotrail.fir.errors

import com.kitakkun.kotrail.exclude.Glob
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirCatch
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirThrowExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.types.abbreviatedTypeOrSelf
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * The fully qualified names the clause's type goes by. `kotlin.Exception` is a type alias of
 * `java.lang.Exception` on the JVM: the name as written and the class it expands to both count.
 */
fun FirCatch.caughtTypeNames(session: FirSession): List<String> {
    val written = parameter.returnTypeRef.coneType
    return listOfNotNull(written.classId, written.fullyExpandedType(session).classId, written.abbreviatedTypeOrSelf.classId)
        .map { it.asSingleFqName().asString() }
}

/** Whether the clause throws its parameter anywhere, or ends in a throw of anything. */
fun FirCatch.rethrows(): Boolean {
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

/**
 * Whether the clause only forwards its parameter: every statement passes it whole as an
 * argument of a call that is not a logger (`onError(e)`), assigns it whole (`state.failure = e`),
 * or throws it, bare or as a constructor argument (`throw e`, `throw Wrapped(e)`). No other read
 * of it is allowed anywhere in the clause: no `is` check, no `when (e)`, no `e.message`.
 */
fun FirCatch.forwards(loggers: List<Glob>): Boolean {
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

package com.kitakkun.kotrail.fir

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirCatch
import org.jetbrains.kotlin.fir.expressions.FirLoop
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenBranch
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isUnit

/**
 * Whether anything consumes the value of the expression [elements] ends with, [elements] being
 * the chain of its parents from the outside in (a checker's `containingElements`). It is dropped
 * when the expression is a statement that is not its block's last, or the last statement of a
 * block whose own value is dropped: a function body, a lambda that returns Unit (FIR wraps a
 * lambda's last statement in an implicit return only otherwise), a loop body, a `finally` block,
 * or a branch of an `if`/`when` or a `try` whose value is dropped in turn. A safe call yields what
 * its selector does. Any other parent consumes the value.
 */
internal fun isValueUsed(elements: List<FirElement>): Boolean {
    var child = elements.lastOrNull() ?: return true
    for (index in elements.size - 2 downTo 0) {
        val parent = elements[index]
        when (parent) {
            is FirSafeCallExpression -> if (parent.selector !== child) return true
            is FirBlock -> if (parent.statements.lastOrNull() !== child) return false
            is FirWhenBranch -> if (parent.result !== child) return true
            is FirWhenExpression -> if (child !is FirWhenBranch) return true
            is FirCatch -> if (parent.block !== child) return true
            is FirTryExpression -> if (parent.finallyBlock === child) return false
            is FirLoop -> return parent.condition === child
            is FirFunction, is FirAnonymousInitializer -> return false
            is FirReturnExpression -> return !parent.target.labeledElement.returnTypeRef.coneType.isUnit
            else -> return true
        }
        child = parent
    }
    return true
}

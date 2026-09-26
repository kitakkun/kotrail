package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.concurrency.AsyncWorkService
import com.kitakkun.kotrail.fir.concurrency.asyncWorkService
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.test.isTestFunction
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.isConst
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.text

/**
 * Reports a fixed wait that stands in for awaiting work a call just started:
 *
 * ```kotlin
 * manager.reconnect()      // fun reconnect() { scope.launch { ... } }
 * delay(500)               // reported: guesses how long reconnect takes
 * send(hello)
 * ```
 *
 * Too short and the code after the wait runs before the work is done; too long and it waits for
 * nothing; either way the timing is a guess that breaks on a slow machine. The fix is a handle:
 * make the function suspend, or have it return its `Job` or `Deferred`, and await that. A
 * function starts such work when its body calls a starter (`launch`, `async`, `Thread.start`, a
 * posted runnable; `delayForCompletion.starters`) and drops the result, or calls a function that
 * does; the answer comes from the session's [com.kitakkun.kotrail.fir.concurrency.AsyncWorkService],
 * which reads `@InferredStartsAsyncWork` for a function on the classpath.
 *
 * Reported: a call to one of `delays` with a fixed amount (a literal, a constant, or a value
 * built only from those, `500.milliseconds` included), where some statement earlier in the same
 * block, with no suspending call or other wait in between, starts such work. Quiet: a computed
 * amount, a wait inside a loop or a `repeat`/`retry` lambda (polling has a reason to wait), a
 * test function (`test.noSleep` owns those), and a wait with nothing started before it.
 */
object DelayForCompletionChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    private val POLLING_LAMBDAS = setOf("kotlin.repeat")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.DELAY_FOR_COMPLETION)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val callee = expression.calleeReference.toResolvedCallableSymbol() ?: return
        val delays = config.asyncWork.delays
        if (callee.callableId?.asSingleFqName()?.asString() !in delays) return
        val amount = expression.argumentList.arguments.firstOrNull() ?: return
        if (!amount.isFixed()) return

        val session = context.session
        val elements = context.containingElements
        val function = elements.lastOrNull { it is FirNamedFunction } as? FirNamedFunction
        if (function != null && function.symbol.isTestFunction(session, config.test.annotations)) return
        if (inLoop(elements)) return

        // The statements before the wait, in its own block and in every enclosing block up to the function or
        // lambda that holds it: `if (needed) delay(500)` waits for what the statements before the `if` started.
        val service = session.asyncWorkService
        for ((blockIndex, element) in elements.withIndex().reversed()) {
            if (element is FirAnonymousFunction || element is FirNamedFunction) return
            val block = element as? FirBlock ?: continue
            val statement = elements.getOrNull(blockIndex + 1) as? FirStatement ?: expression
            val index = block.statements.indexOfFirst { it === statement }
            if (index < 0) continue
            for (earlier in block.statements.subList(0, index).asReversed()) {
                val started = service.startingCallee(earlier)
                if (started != null) {
                    reportKotrail(source, KotrailDiagnostics.DELAY_WAITS_FOR_ASYNC_WORK, source.text.toString(), howToWait(started))
                    return
                }
                // A suspending call, or another wait, between the start and this wait: the wait is about something else.
                if (earlier.suspends(delays)) return
            }
        }
    }

    /** What started the work and the proper way to wait for it: join the handle a starter returns, or await what a function should hand back. */
    private fun howToWait(started: AsyncWorkService.Started): String {
        val name = started.name
        return when (started.starter) {
            null -> "'$name' started; make it suspend, or have it return its Job or Deferred, and await that"
            "kotlinx.coroutines.launch", "kotlinx.coroutines.flow.launchIn" -> "'$name' started; keep the Job and join it"
            "kotlinx.coroutines.async" -> "'$name' started; keep the Deferred and await it"
            "java.lang.Thread.start", "kotlin.concurrent.thread" -> "'$name' started; keep the Thread and join it"
            else -> "'$name' started; keep what it returns and wait on that, or do the work here"
        }
    }

    /** Whether the wait sits in a loop body, or in the lambda of a polling helper (`repeat`, `retry`). */
    private fun inLoop(elements: List<FirElement>): Boolean {
        for ((index, element) in elements.withIndex()) {
            when (element) {
                is FirWhileLoop, is FirDoWhileLoop -> return true
                is FirAnonymousFunction -> {
                    val call = elements.take(index).lastOrNull { it is FirFunctionCall } as? FirFunctionCall ?: continue
                    val callee = call.calleeReference.toResolvedCallableSymbol() ?: continue
                    val name = callee.callableId?.asSingleFqName()?.asString() ?: continue
                    if (name in POLLING_LAMBDAS || callee.name.asString().startsWith("retry")) return true
                }
                else -> {}
            }
        }
        return false
    }

    /** A literal, a constant, a qualifier, or a call whose receiver and arguments are all of those: `500`, `TIMEOUT`, `500.milliseconds`. */
    private fun FirExpression.isFixed(): Boolean = when (this) {
        is FirLiteralExpression -> true
        is FirNamedArgumentExpression -> expression.isFixed()
        is FirSmartCastExpression -> originalExpression.isFixed()
        is FirResolvedQualifier -> true
        is FirPropertyAccessExpression -> (calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol)?.isConst == true
        is FirFunctionCall -> (explicitReceiver?.isFixed() ?: true) && argumentList.arguments.all { it.isFixed() }
        else -> false
    }

    /** Whether the statement contains a suspending call or one of the waits, outside lambdas. */
    private fun FirStatement.suspends(delays: List<String>): Boolean {
        var found = false
        accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (found) return
                element.acceptChildren(this)
            }

            override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {}

            override fun visitFunctionCall(functionCall: FirFunctionCall) {
                if (found) return
                val callee = functionCall.calleeReference.toResolvedCallableSymbol()
                if ((callee as? FirNamedFunctionSymbol)?.isSuspend == true || callee?.callableId?.asSingleFqName()?.asString() in delays) {
                    found = true
                    return
                }
                functionCall.acceptChildren(this)
            }
        })
        return found
    }
}

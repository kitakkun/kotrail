package com.kitakkun.kotrail.fir.test.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.test.isTestFunction
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Reports a wait on real time inside a test:
 *
 * ```kotlin
 * @Test fun `emits after a tick`() {
 *     Thread.sleep(500)                      // reported
 *     runBlocking { delay(500) }             // reported: real time
 * }
 *
 * @Test fun `emits after a tick`() = runTest {
 *     delay(500)                             // fine: virtual time, returns at once
 * }
 * ```
 *
 * A fixed wait is either too long (the suite is slow) or too short (the test is flaky), and it
 * is both on different machines. The functions that wait real time are
 * `test.noSleep.functions`; `kotlinx.coroutines.delay` is reported as well, except inside the
 * lambda of a `test.noSleep.virtualTime` function (`runTest` by default), where it is a
 * virtual-time skip. Which annotations mark a test is `test.annotations`.
 */
object TestSleepChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    private const val DELAY = "kotlinx.coroutines.delay"

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.TEST_NO_SLEEP)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (!declaration.symbol.isTestFunction(context.session, config.test.annotations)) return
        val body = declaration.body ?: return

        val walker = WaitWalker(
            realTime = config.test.sleepFunctions.toSet(),
            virtualTime = config.test.virtualTimeFunctions.toSet(),
        ) { call, name ->
            val target = call.calleeReference.source ?: call.source ?: return@WaitWalker
            reportKotrail(target, KotrailDiagnostics.TEST_REAL_TIME_WAIT, name)
        }
        body.accept(walker)
    }

    private class WaitWalker(
        private val realTime: Set<String>,
        private val virtualTime: Set<String>,
        private val report: (FirFunctionCall, String) -> Unit,
    ) : FirVisitorVoid() {
        private var onVirtualTime = false

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            val callee = functionCall.calleeReference.toResolvedNamedFunctionSymbol()
            val fqn = callee?.callableId?.asSingleFqName()?.asString()
            when {
                fqn == null -> {}
                fqn in realTime -> report(functionCall, callee.name.asString())
                fqn == DELAY && !onVirtualTime -> report(functionCall, callee.name.asString())
            }
            if (fqn != null && fqn in virtualTime) {
                val outer = onVirtualTime
                onVirtualTime = true
                functionCall.acceptChildren(this)
                onVirtualTime = outer
            } else {
                functionCall.acceptChildren(this)
            }
        }
    }
}

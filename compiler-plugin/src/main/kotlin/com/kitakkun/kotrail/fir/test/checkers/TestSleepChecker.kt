package com.kitakkun.kotrail.fir.test.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.test.isTestFunction
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId

/**
 * Reports a wait on real time inside a test:
 *
 * ```kotlin
 * @Test fun `emits after a tick`() {
 *     Thread.sleep(500)                      // reported
 *     runBlocking { delay(500) }             // reported: real time
 *     awaitSize(3)                           // reported when awaitSize, a helper of this test, sleeps
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
 * virtual-time skip. A wait does not escape by moving into a helper: calls into the test's own
 * helpers (a private function, or a member of the test's class) are followed, and the call in
 * the test is what gets reported. Which annotations mark a test is `test.annotations`.
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
            testClass = declaration.symbol.callableId?.classId,
        ) { call, description ->
            val target = call.calleeReference.source ?: call.source ?: return@WaitWalker
            reportKotrail(target, KotrailDiagnostics.TEST_REAL_TIME_WAIT, description)
        }
        body.accept(walker)
    }

    /**
     * Walks a test body. A call to one of the test's own helpers is followed into the helper's
     * body, with the virtual-time state of the call site; the first real wait found there is
     * reported on the call in the test, naming both.
     */
    private class WaitWalker(
        private val realTime: Set<String>,
        private val virtualTime: Set<String>,
        private val testClass: ClassId?,
        private val report: (FirFunctionCall, String) -> Unit,
    ) : FirVisitorVoid() {
        private var onVirtualTime = false
        private val helpersOnStack = mutableSetOf<FirNamedFunctionSymbol>()

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            val callee = functionCall.calleeReference.toResolvedNamedFunctionSymbol()
            val fqn = callee?.callableId?.asSingleFqName()?.asString()
            when {
                callee == null || fqn == null -> {}
                fqn in realTime -> report(functionCall, callee.name.asString())
                fqn == DELAY && !onVirtualTime -> report(functionCall, callee.name.asString())
                callee.isOwnHelper() -> firstWaitIn(callee)?.let { wait ->
                    report(functionCall, "${callee.name.asString()}, which calls $wait")
                }
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

        /** A private function, or a member of the test's own class: helpers that belong to this test file. */
        private fun FirNamedFunctionSymbol.isOwnHelper(): Boolean {
            if (!origin.fromSource) return false
            if (visibility == Visibilities.Private) return true
            return testClass != null && callableId?.classId == testClass
        }

        /** The name of the first real wait in the helper's body, in the caller's virtual-time state, or `null`. */
        @OptIn(SymbolInternals::class)
        private fun firstWaitIn(helper: FirNamedFunctionSymbol): String? {
            if (!helpersOnStack.add(helper)) return null
            var found: String? = null
            val inner = WaitWalker(realTime, virtualTime, testClass) { _, description -> if (found == null) found = description }
            inner.onVirtualTime = onVirtualTime
            inner.helpersOnStack += helpersOnStack
            helper.fir.body?.accept(inner)
            helpersOnStack.remove(helper)
            return found
        }
    }
}

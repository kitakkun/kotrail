package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.concurrency.asyncWorkService
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.declarations.utils.visibility

/**
 * Reports a non-suspending function that starts work and gives its caller nothing to wait for:
 *
 * ```kotlin
 * fun reconnect() { scope.launch { socket.reopen() } }      // reported: the Job is dropped
 * fun reconnect(): Job = scope.launch { socket.reopen() }   // fine: the caller can join it
 * suspend fun reconnect() { socket.reopen() }               // fine: the caller awaits it
 * ```
 *
 * Fire-and-forget is what forces callers into `delay(500)` guesses (see
 * [DelayForCompletionChecker]); this rule reports the producer side. It is off by default:
 * an event handler that launches and returns is the ordinary shape of UI code, and the rule
 * is for the modules where a dropped handle is a bug. Skipped: `suspend`, private and local
 * functions, overrides (the signature is fixed elsewhere), `main`, and functions named `on...`
 * (event handlers). Only the body's own starter calls count, not those of the functions it
 * calls; the rule names the declaration to fix.
 *
 * Whether the rule is on or not, every named function seen here warms the session's
 * [com.kitakkun.kotrail.fir.concurrency.AsyncWorkService], so that the metadata writer and
 * cross-module callers find the answer cached.
 */
object FireAndForgetLaunchChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        val enabled = config.isEnabled(KotrailRule.FIRE_AND_FORGET_LAUNCH)
        if (!enabled && !config.isEnabled(KotrailRule.DELAY_FOR_COMPLETION)) return
        if (declaration.isSuspend || declaration.body == null) return
        val service = context.session.asyncWorkService
        service.startsAsyncWork(declaration.symbol)
        if (!enabled) return

        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.isOverride) return
        val visibility = declaration.visibility
        if (visibility == Visibilities.Private || visibility == Visibilities.Local) return
        val name = declaration.name.asString()
        if (name == "main" || name.isEventHandlerName()) return

        val starter = service.discardedStarter(declaration.symbol) ?: return
        reportKotrail(source, KotrailDiagnostics.FIRE_AND_FORGET_LAUNCH, name, starter)
    }

    /** `onClick`, `onResume`: a handler that launches and returns is the ordinary shape. */
    private fun String.isEventHandlerName(): Boolean = length > 2 && startsWith("on") && this[2].isUpperCase()
}

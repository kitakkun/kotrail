package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.effects.EffectCaptureAnalysis
import com.kitakkun.kotrail.fir.compose.effects.effectCaptureService
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.compose.live.LiveValueAnalysis
import com.kitakkun.kotrail.fir.isOfThisModule
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol

/**
 * The missing-key half of `compose.rememberKeys`: asks that the lambda of `remember`,
 * `LaunchedEffect` and their kin not freeze a value of the enclosing composable that its keys do
 * not cover ([ComposableConstantKeyChecker] reports the other half, a key that never changes):
 *
 * ```kotlin
 * @Composable
 * fun Price(amount: Long, events: Flow<Event>, onEvent: (Event) -> Unit) {
 *     val text = remember { format(amount) }                       // reported: amount, add it to the keys
 *     LaunchedEffect(Unit) { events.collect { onEvent(it) } }      // reported: onEvent, read it through rememberUpdatedState
 *     val text = remember(amount) { format(amount) }               // fine
 * }
 * ```
 *
 * A lambda keyed on nothing runs once and keeps the first value of whatever it captured. The
 * message lays out the three choices: a key when the work should run again as the value changes,
 * `rememberUpdatedState` when only the latest value matters while it runs, and a stable holder
 * (a remembered object, a member `val`) when the value never changes, which the rule does not
 * report at all. Where one choice is the usual answer, the value carries it: a callback captured
 * by an effect is read through `rememberUpdatedState`, since restarting the effect because a
 * lambda changed identity is itself the classic bug; a data value a `remember { }` computes from
 * belongs in the keys. A snapshot collection whose contents the lambda reads gets neither: it keeps
 * its identity while its contents change, so a key on it never restarts anything, and the message
 * asks for `derivedStateOf` or `snapshotFlow`, or a key on a snapshot of it. What counts as
 * captured, and what does not, is decided by [EffectCaptureAnalysis].
 *
 * While `compose.liveValueRemembered` is on, a value it reports as read live in a `remember`
 * (`remember(query) { items.filter { ... } }` over a snapshot `items`) is its finding alone: the
 * missing keys of that `remember` leave it out, and the others are reported as before.
 *
 * The same applies through a helper that hides the effect:
 *
 * ```kotlin
 * @Composable fun ActionEffect(block: (Action) -> Unit) { LaunchedEffect(Unit) { actions.collect { block(it) } } }
 * ActionEffect { onNavigate(it) }                                 // reported on the lambda: onNavigate
 * ```
 *
 * `ActionEffect` keeps `block` for the life of its effect (reported inside it as above), so the
 * lambda a caller passes is kept too, and the callbacks it reads go stale. A composable's
 * captured parameters are computed from its body and written as `@InferredEffectCapture`
 * metadata; a call into another module that hands a lambda reading a callback, or a callback
 * parameter itself, for a captured parameter is reported at the argument. Inside the module the
 * helper's own report is the one to act on, so its callers stay quiet. Only callbacks count at
 * the call site: a data object handed along (a back stack, a channel) keeps its identity across
 * recompositions in practice, and reporting it repeated one helper finding per caller.
 */
object ComposableRememberKeysChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    private const val ADVICE_KEYS = "add it to the keys"
    private const val ADVICE_UPDATED_STATE = "read it through rememberUpdatedState"
    private const val ADVICE_OBSERVE = "read it inside derivedStateOf or snapshotFlow, or key on a snapshot of it: " +
        "as a key it never changes when its contents do"

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val session = context.session
        val config = session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_REMEMBER_KEYS)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val function = context.containingElements.lastOrNull { it is FirNamedFunction } as? FirNamedFunction ?: return
        if (!function.symbol.isComposable(session)) return
        val keyedFunctions = config.compose.rememberKeysFunctions
        // Bodies are gone by the time the IR writer runs: every composable a call sits in is summarized now.
        val service = session.effectCaptureService
        service.capturedParameters(function.symbol)

        val keyed = EffectCaptureAnalysis.keyedCall(session, keyedFunctions, function, expression)
        if (keyed != null) {
            val reportedLive = liveReadsReportedElsewhere(expression)
            val missing = keyed.missing.filter { EffectCaptureAnalysis.counts(keyed, it) && it.symbol !in reportedLive }.map { value ->
                when {
                    value.isFunctionTyped -> "${value.name} ($ADVICE_UPDATED_STATE)"
                    value.readsSnapshotContents -> "${value.name} ($ADVICE_OBSERVE)"
                    !keyed.isEffect -> "${value.name} ($ADVICE_KEYS)"
                    else -> value.name
                }
            }
            if (missing.isEmpty()) return
            reportKotrail(source, KotrailDiagnostics.EFFECT_KEY_MISSING, keyed.callee.name.asString(), missing.joinToString("; "))
            return
        }

        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        if (callee.isOfThisModule(session)) return
        if (callee.callableId.asSingleFqName().asString() in keyedFunctions) return
        if (!callee.isComposable(session)) return
        val captured = service.capturedParameters(callee)
        if (captured.isEmpty()) return
        val at = source.startOffset
        for ((argument, parameter) in expression.resolvedArgumentMapping.orEmpty()) {
            if (parameter.name.asString() !in captured) continue
            val values = EffectCaptureAnalysis.handedValues(session, keyedFunctions, function, argument, at)
            if (values.isEmpty()) continue
            val argumentSource = argument.source ?: continue
            reportKotrail(
                argumentSource,
                KotrailDiagnostics.EFFECT_CAPTURED_BY_CALLEE,
                "''${callee.name.asString()}'' (parameter ''${parameter.name.asString()}'')",
                values.joinToString(", "),
            )
        }
    }

    /**
     * The declarations that `compose.liveValueRemembered` reports as read live in [call], when it is
     * on and [call] is a `remember` it checks; empty otherwise.
     */
    context(context: CheckerContext)
    private fun liveReadsReportedElsewhere(call: FirFunctionCall): Set<FirBasedSymbol<*>> {
        val session = context.session
        if (!session.kotrailConfig.isEnabled(KotrailRule.COMPOSE_LIVE_VALUE_REMEMBERED)) return emptySet()
        val remember = LiveValueAnalysis.rememberCall(call) ?: return emptySet()
        return LiveValueAnalysis.staleReads(session, remember).mapNotNullTo(HashSet()) { it.declaration }
    }
}

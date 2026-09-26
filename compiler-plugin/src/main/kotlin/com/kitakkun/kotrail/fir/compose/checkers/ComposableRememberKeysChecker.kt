package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.effects.EffectCaptureAnalysis
import com.kitakkun.kotrail.fir.compose.effects.effectCaptureService
import com.kitakkun.kotrail.fir.compose.isComposable
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

/**
 * Asks that the lambda of `remember`, `LaunchedEffect` and their kin not freeze a value of the
 * enclosing composable that its keys do not cover:
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
 * A lambda keyed on nothing runs once and keeps the first value of whatever it captured. There
 * are two right fixes, and the message says which applies to each value: a callback captured by
 * an effect is read through `rememberUpdatedState`, since restarting the effect because a lambda
 * changed identity is itself the classic bug; a data value a `remember { }` computes from
 * belongs in the keys; a data value read by a long-lived effect body (a `collect`, a loop,
 * `onDispose`) goes in the keys if the work should restart when it changes, and through
 * `rememberUpdatedState` otherwise. What counts as captured, and what does not, is decided by
 * [EffectCaptureAnalysis].
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
    private const val ADVICE_EITHER = "add it to the keys if the work should restart when it changes, otherwise read it through rememberUpdatedState"

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
            val missing = keyed.missing.filter { EffectCaptureAnalysis.counts(keyed, it) }.map { value ->
                val advice = when {
                    value.isFunctionTyped -> ADVICE_UPDATED_STATE
                    !keyed.isEffect -> ADVICE_KEYS
                    else -> ADVICE_EITHER
                }
                "${value.name} ($advice)"
            }
            if (missing.isEmpty()) return
            reportKotrail(source, KotrailDiagnostics.EFFECT_KEY_MISSING, keyed.callee.name.asString(), missing.joinToString("; "))
            return
        }

        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        // A helper of this module is reported at its own effect; the call is where the fix belongs only for a helper compiled elsewhere.
        if (callee.origin.fromSource) return
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
}

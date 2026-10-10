package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.compose.live.LiveValueAnalysis
import com.kitakkun.kotrail.fir.compose.live.identityKeyedParametersService
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.coneType

/**
 * Asks that a `remember` not keep a copy of a value that changes in place under a key that does
 * not change with it:
 *
 * ```kotlin
 * @Composable
 * fun TrafficTab(transactions: List<Transaction>) {
 *     var query by remember { mutableStateOf("") }
 *     val visible = remember(transactions, query) { transactions.filter { it.url.contains(query) } }
 * }
 *
 * val transactions = remember { mutableStateListOf<Transaction>() }
 * TrafficTab(transactions)                       // reported: TrafficTab remembers a copy keyed on it
 * ```
 *
 * `remember` compares its keys by identity, and its calculation is not observed: the caller hands
 * the same `SnapshotStateList` on every composition, so the key never changes after the first
 * run, and the filtered copy stays as it was when items are added in place. `derivedStateOf`,
 * whose reads are tracked, follows the contents.
 *
 * Two findings. Inside the calculation of a `remember` or `rememberSaveable`, a read of a value
 * that changes in place and that no key reads too: the contents of a snapshot collection
 * (`SnapshotStateList`, `SnapshotStateMap`, `SnapshotStateSet`), a property delegated to a
 * `State`, or a `State`'s `value` (`KOTRAIL_LIVE_VALUE_REMEMBERED`, on the call). At a call to a
 * composable, a snapshot collection or a `State` handed to a parameter that the callee keys a
 * remembered copy on, as its `identityKeyedParameters` fact says, as the argument or as a branch
 * of it (`if (redact) transactions.map(::redact) else transactions`)
 * (`KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE`, on the argument; through wrappers, it
 * names the composable further down that holds the `remember`, as the fact records it). What the
 * calculation reads, and what counts as live, is decided by [LiveValueAnalysis].
 *
 * A parameter declared as a snapshot collection or a `State` is left to the first finding, made
 * inside the callee where the fix belongs, so its callers stay quiet.
 */
object ComposableLiveValueRememberedChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val session = context.session
        if (!session.kotrailConfig.isEnabled(KotrailRule.COMPOSE_LIVE_VALUE_REMEMBERED)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return

        val remember = LiveValueAnalysis.rememberCall(expression)
        if (remember != null) {
            val stale = LiveValueAnalysis.staleReads(session, remember)
            if (stale.isEmpty()) return
            // The message quotes the argument; a list of values is quoted value by value.
            reportKotrail(source, KotrailDiagnostics.LIVE_VALUE_REMEMBERED, stale.joinToString("', '") { it.name })
            return
        }

        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        if (!callee.isComposable(session)) return
        val keyed = session.identityKeyedParametersService.identityKeyedParameters(callee)
        if (keyed.isEmpty()) return
        for ((argument, parameter) in expression.resolvedArgumentMapping.orEmpty()) {
            val name = parameter.name.asString()
            if (name !in keyed) continue
            if (argument is FirVarargArgumentsExpression) continue
            // Declared live: the remember inside the callee is reported, and that is where the fix belongs.
            if (LiveValueAnalysis.isLiveType(session, parameter.returnTypeRef.coneType)) continue
            // The argument itself, or the branch of an if, a when or an elvis that hands the live value on: what the message names.
            val live = LiveValueAnalysis.liveValueOf(session, argument) ?: continue
            val argumentSource = argument.source ?: continue
            if (argumentSource.kind is KtFakeSourceElementKind) continue
            val text = LiveValueAnalysis.shortText(live) ?: continue
            val calleeName = callee.name.asString()
            // Through wrappers the remember is further down, in the composable the fix belongs to.
            val holder = keyed[name]
            val subject = if (holder == null) "'$calleeName'" else "'$calleeName' hands it to '$holder', which"
            reportKotrail(argumentSource, KotrailDiagnostics.LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE, text, subject, holder ?: calleeName)
        }
    }
}

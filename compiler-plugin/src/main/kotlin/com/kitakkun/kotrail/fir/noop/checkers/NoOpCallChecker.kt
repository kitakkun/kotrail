package com.kitakkun.kotrail.fir.noop.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.noop.noOpService
import com.kitakkun.kotrail.fir.preconditions.knownArgumentValues
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.test.isTestFunction
import com.kitakkun.kotrail.fir.test.isTestSourcePath
import com.kitakkun.kotrail.preconditions.Value
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.unwrapFakeOverrides

/**
 * Reports a call whose arguments make the callee return at once without doing anything:
 *
 * ```kotlin
 * fun send(bytes: ByteArray) {
 *     if (bytes.isEmpty()) return
 *     socket.write(bytes)
 * }
 * send(byteArrayOf())                         // reported: send returns at once when bytes.isEmpty()
 * delay(0)                                    // reported: delay returns at once when timeMillis <= 0
 * send(payload)                               // unknown, never reported
 * ```
 *
 * The conditions are the callee's `noOpWhen` fact ([com.kitakkun.kotrail.fir.noop.NoOpService]).
 * Arguments are folded as for preconditions; a condition is reported only when every parameter
 * it mentions is known and it holds. Test functions and test source sets are not looked at.
 */
object NoOpCallChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val session = context.session
        val config = session.kotrailConfig
        if (!config.isEnabled(KotrailRule.NO_OP_CALL)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        // A member reached through a subclass or a substituted type is the declaration that runs.
        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol()?.unwrapFakeOverrides() ?: return
        val fact = session.noOpService.of(callee)
        if (fact.conditions.isEmpty()) return
        if (inTest(session, config.test.annotations)) return

        val values = expression.knownArgumentValues(session, callee)
        if (values.isEmpty()) return
        val condition = fact.conditions.firstOrNull { condition ->
            values.keys.containsAll(condition.parameters()) && (condition.evaluate(values) as? Value.BoolV)?.value == true
        } ?: return
        val text = condition.render() ?: return
        val hint = if (fact.hint.isEmpty()) "" else " ${fact.hint}"
        reportKotrail(source, KotrailDiagnostics.CALL_DOES_NOTHING, callee.callableId.callableName.asString(), "$text.$hint")
    }

    context(context: CheckerContext)
    private fun inTest(session: FirSession, annotations: List<String>): Boolean {
        val path = context.containingFileSymbol?.sourceFile?.path
        if (path != null && isTestSourcePath(path)) return true
        return context.containingDeclarations.any { it is FirNamedFunctionSymbol && it.isTestFunction(session, annotations) }
    }
}

@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.preconditions.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.preconditions.CondConverter
import com.kitakkun.kotrail.fir.preconditions.preconditionService
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.preconditions.Value
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.renderReadable

/**
 * Evaluates the callee's preconditions against the arguments of a call and reports the ones that
 * are certainly false:
 *
 * ```kotlin
 * fun retry(times: Int) { require(times >= 0) }
 * retry(-1)                                   // reported: retry requires times >= 0 (times = -1)
 * val attempts = base * 2                     // base is a const val
 * retry(attempts)                             // folded through the local, reported if negative
 * retry(readInput())                          // unknown, never reported
 * ```
 *
 * Arguments are folded with [CondConverter], so literals, `const val`s, final `val`s with a
 * constant initializer, local `val`s, arithmetic, string templates, and constant `if` / `?:`
 * all count. Parameters left to their defaults use the default expression when the callee is in
 * this module. A condition with any unknown input is not reported.
 */
object PreconditionChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.PRECONDITIONS)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val session = context.session
        val callee = expression.calleeReference.toResolvedFunctionSymbol() ?: return
        val conditions = session.preconditionService.preconditionsOf(callee)
        if (conditions.isEmpty()) return

        val mapping = expression.resolvedArgumentMapping ?: return
        val env = HashMap<String, Value>()
        val argumentConverter = CondConverter(session, emptyMap())
        val passed = HashSet<String>()
        for ((argument, parameter) in mapping) {
            val name = parameter.name.asString()
            passed += name
            if (parameter.isVararg) continue
            argumentConverter.convert(argument)?.evaluate(emptyMap())?.let { env[name] = it }
        }
        // Defaulted parameters: the default expression may refer to earlier parameters.
        if (callee.origin.fromSource) {
            val calleeParams = callee.valueParameterSymbols.associate { it as FirBasedSymbol<*> to it.name.asString() }
            val defaultConverter = CondConverter(session, calleeParams)
            for (parameter in callee.valueParameterSymbols) {
                val name = parameter.name.asString()
                if (name in passed) continue
                val default = parameter.fir.defaultValue ?: continue
                defaultConverter.convert(default)?.evaluate(env)?.let { env[name] = it }
            }
        }
        if (env.isEmpty()) return

        for (condition in conditions) {
            val result = condition.evaluate(env) as? Value.BoolV ?: continue
            if (result.value) continue
            val text = condition.render() ?: continue
            val bindings = condition.parameters().filter { it in env }.joinToString(", ") { "$it = ${env.getValue(it).render()}" }
            reportKotrail(source, KotrailDiagnostics.PRECONDITION_VIOLATED, callee.displayName(), "$text ($bindings)")
        }
    }

    private fun FirFunctionSymbol<*>.displayName(): String = when (this) {
        is FirNamedFunctionSymbol -> callableId.callableName.asString()
        is FirConstructorSymbol -> resolvedReturnTypeRef.coneType.renderReadable()
        else -> name.asString()
    }
}

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol

/**
 * Rejects calls to callables the project has listed in `forbiddenCall.functions`:
 *
 * ```kotlin
 * println("debug")          // forbiddenCall.functions=kotlin.io.println
 * Thread.sleep(100)         // forbiddenCall.functions=java.lang.Thread.sleep
 * Date()                    // forbiddenCall.functions=java.util.Date
 * GlobalScope.launch { }    // forbiddenCall.functions=kotlinx.coroutines.GlobalScope.launch
 * ```
 *
 * Every entry is a fully qualified name. Top-level functions are `package.name`, members (including
 * Java static methods and members of objects) are `class.name`, and constructors are the class name.
 * An extension called through an object qualifier (`GlobalScope.launch`) also matches the
 * `object.name` spelling, because that is how readers see the call. Only function and constructor
 * calls are inspected; property accesses and callable references are not. Nothing is reported when
 * the list is empty or the call has no real source (compiler-generated calls such as the `iterator()`
 * of a `for` loop).
 */
object ForbiddenCallChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.FORBIDDEN_CALL)) return
        val forbidden = config.forbiddenCall.functions
        if (forbidden.isEmpty()) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return

        val callee = expression.calleeReference.toResolvedFunctionSymbol() ?: return
        val candidates = candidateNames(expression, callee)
        val match = forbidden.firstOrNull { it in candidates } ?: return
        reportKotrail(source, KotrailDiagnostics.FORBIDDEN_CALL, match)
    }

    /** The spellings under which [callee] may be listed in the configuration. */
    private fun candidateNames(call: FirFunctionCall, callee: FirFunctionSymbol<*>): Set<String> {
        return when (callee) {
            // `java.util.Date()`: the class name stands for its constructors.
            is FirConstructorSymbol -> setOfNotNull(callee.callableId.classId?.asSingleFqName()?.asString())
            is FirNamedFunctionSymbol -> {
                // Local functions live in the `<local>` package, so they never match a configured name.
                val callableId = callee.callableId
                val names = mutableSetOf(callableId.asSingleFqName().asString())
                // `GlobalScope.launch { }`: an extension reached through an object qualifier.
                val qualifier = (call.explicitReceiver as? FirResolvedQualifier)?.classId
                if (qualifier != null) {
                    names += qualifier.asSingleFqName().child(callableId.callableName).asString()
                }
                names
            }
            else -> emptySet()
        }
    }
}

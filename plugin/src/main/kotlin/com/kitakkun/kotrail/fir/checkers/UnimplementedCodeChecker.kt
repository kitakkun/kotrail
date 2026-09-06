package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Rejects the standard library's placeholders for code that has not been written:
 *
 * ```kotlin
 * fun load(): User = TODO("wire the repository")     // reported
 * override fun save() { throw NotImplementedError() } // reported
 * ```
 *
 * `TODO()` compiles, type-checks as anything, and throws the first time it runs, which makes it
 * the natural way for a generated draft to look finished. This rule turns the placeholder into a
 * compile error. It is meant to be switched off for source sets where a stub is acceptable
 * (tests, debug variants) through a separate configuration file, and left on for the build that
 * ships.
 *
 * Only calls to `kotlin.TODO` and constructions of `kotlin.NotImplementedError` are inspected.
 * `// TODO` comments are not a compile-time concern; user-defined functions named `TODO` are not
 * matched because the callable's fully qualified name is compared.
 */
object UnimplementedCodeChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    private val TODO = CallableId(FqName("kotlin"), Name.identifier("TODO"))
    private val NOT_IMPLEMENTED_ERROR = ClassId(FqName("kotlin"), Name.identifier("NotImplementedError"))

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.NO_UNIMPLEMENTED)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return

        val found = when (val callee = expression.calleeReference.toResolvedFunctionSymbol()) {
            is FirNamedFunctionSymbol -> "TODO()".takeIf { callee.callableId == TODO }
            is FirConstructorSymbol -> "NotImplementedError".takeIf { callee.callableId.classId == NOT_IMPLEMENTED_ERROR }
            else -> null
        } ?: return
        reporter.reportOn(source, KotrailDiagnostics.UNIMPLEMENTED_CODE.at(config.severity(KotrailRule.NO_UNIMPLEMENTED)), found)
    }
}

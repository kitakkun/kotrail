package com.kitakkun.kotrail.fir.compose.locals.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.compose.isPreview
import com.kitakkun.kotrail.fir.compose.locals.CompositionLocalNames
import com.kitakkun.kotrail.fir.compose.locals.CompositionLocalService
import com.kitakkun.kotrail.fir.compose.locals.LocalsAnalysis
import com.kitakkun.kotrail.fir.compose.locals.compositionLocalService
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol

/**
 * Reports, at a root of composition, every required composition local that is read somewhere
 * below the root and never provided. A root is a composable annotated `@CompositionLocalRoot`
 * or a `@Preview`; the content lambdas of entry points such as `setContent` are handled by
 * [CompositionLocalEntryPointChecker].
 *
 * For every other composable this checker still runs the analysis so that the result is cached
 * while FIR bodies are available; the IR metadata writer reads that cache after Fir2Ir.
 */
object CompositionLocalRootChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.COMPOSE_COMPOSITION_LOCALS)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val symbol = declaration.symbol
        if (!symbol.isComposable(context.session)) return
        val service = context.session.compositionLocalService
        val analysis = service.analysis(symbol)
        val isRoot = symbol.hasAnnotation(CompositionLocalNames.COMPOSITION_LOCAL_ROOT, context.session) ||
            symbol.isPreview(context.session)
        if (!isRoot) return
        reportMissing(source, service, analysis)
    }
}

/**
 * Treats the composable lambda passed to an entry point (`setContent { }`, `Window { }`, ...,
 * the functions in `compose.compositionLocals.roots`) as a root: nothing above it provides
 * locals, so every required local read inside must be provided inside.
 */
object CompositionLocalEntryPointChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_COMPOSITION_LOCALS)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        if (callee.callableId.asSingleFqName().asString() !in config.compose.compositionLocals.roots) return
        val service = context.session.compositionLocalService
        for (argument in expression.arguments) {
            val lambda = (argument.unwrapArgument() as? FirAnonymousFunctionExpression)?.anonymousFunction ?: continue
            val body = lambda.body ?: continue
            reportMissing(source, service, service.readsBelow(body), KotrailDiagnostics.COMPOSITION_LOCAL_NOT_PROVIDED_AT_ENTRY_POINT)
        }
    }
}

/** Caches which source locals have a throwing default, for the IR writer. Reports nothing. */
object CompositionLocalPropertyWarmup : FirPropertyChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.COMPOSE_COMPOSITION_LOCALS)) return
        context.session.compositionLocalService.isRequiredBySource(declaration.symbol)
    }
}

context(context: CheckerContext, reporter: DiagnosticReporter)
private fun reportMissing(
    source: KtSourceElement,
    service: CompositionLocalService,
    analysis: LocalsAnalysis,
    diagnostic: com.kitakkun.kotrail.fir.TunableDiagnostic2<String, String> = KotrailDiagnostics.COMPOSITION_LOCAL_NOT_PROVIDED,
) {
    val platform = context.session.kotrailConfig.compose.compositionLocals.platform
    for ((local, path) in analysis.reads) {
        if (local in platform) continue
        if (!service.isRequired(local, analysis)) continue
        val where = if (path.isEmpty()) "read here" else "read in " + path.joinToString(" > ")
        reportKotrail(source, diagnostic, local, where)
    }
}

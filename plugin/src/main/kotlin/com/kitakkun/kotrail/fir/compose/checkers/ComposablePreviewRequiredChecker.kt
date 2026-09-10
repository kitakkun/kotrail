@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.PreviewScope
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassLikeSymbol
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Every UI composable must have a `@Preview` in the same file that calls it:
 *
 * ```kotlin
 * @Composable fun UserCard(name: String, modifier: Modifier = Modifier) { ... }   // reported without ...
 *
 * @Preview
 * @Composable
 * private fun UserCardPreview() { UserCard(name = "Ada") }                           // ... this
 * ```
 *
 * Keeping the preview next to the component is the convention this rule enforces; it is also
 * what lets the check stay a single-file, frontend check. Multipreview annotations (annotations
 * themselves annotated with `@Preview`) count as previews. The composables inspected are the
 * `Unit`-returning ones whose visibility falls under `compose.preview.requireFor`; preview
 * functions, `override` / `expect` functions, and local functions are left alone.
 */
object ComposablePreviewRequiredChecker : FirFileChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_PREVIEW_REQUIRED)) return
        val session = context.session

        val functions = mutableListOf<FirNamedFunction>()
        collectFunctions(declaration.declarations, functions)
        val previewed = mutableSetOf<FirNamedFunctionSymbol>()
        for (function in functions) {
            if (!function.isPreview(session)) continue
            val collector = CalleeCollector()
            function.body?.accept(collector)
            previewed += collector.callees
        }

        for (function in functions) {
            if (!function.needsPreview(session, config.compose.previewRequireFor)) continue
            if (function.symbol in previewed) continue
            val source = function.source ?: continue
            if (source.kind is KtFakeSourceElementKind) continue
            reportKotrail(source, KotrailDiagnostics.COMPOSABLE_WITHOUT_PREVIEW, function.name.asString())
        }
    }

    /** Top-level functions and members of classes and objects, at any nesting depth. */
    private fun collectFunctions(declarations: List<FirDeclaration>, into: MutableList<FirNamedFunction>) {
        for (declaration in declarations) {
            when (declaration) {
                is FirNamedFunction -> into += declaration
                is FirRegularClass -> collectFunctions(declaration.declarations, into)
                else -> {}
            }
        }
    }

    private fun FirNamedFunction.needsPreview(session: FirSession, scope: PreviewScope): Boolean {
        if (!symbol.isComposable(session)) return false
        if (!returnTypeRef.coneType.isUnit) return false
        if (isOverride || isExpect || isPreview(session)) return false
        return when (scope) {
            PreviewScope.PUBLIC -> visibility == Visibilities.Public
            PreviewScope.INTERNAL -> visibility == Visibilities.Public || visibility == Visibilities.Internal
            PreviewScope.ALL -> visibility != Visibilities.Local
        }
    }

    /** `@Preview` itself, or a multipreview annotation (an annotation class annotated with `@Preview`). */
    private fun FirNamedFunction.isPreview(session: FirSession): Boolean {
        if (symbol.hasAnnotation(ComposeNames.PREVIEW, session)) return true
        return symbol.resolvedAnnotationsWithClassIds.any { annotation ->
            annotation.toAnnotationClassLikeSymbol(session)?.hasAnnotation(ComposeNames.PREVIEW, session) == true
        }
    }

    private class CalleeCollector : FirVisitorVoid() {
        val callees = mutableSetOf<FirNamedFunctionSymbol>()

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            functionCall.calleeReference.toResolvedNamedFunctionSymbol()?.let { callees += it }
            functionCall.acceptChildren(this)
        }
    }
}

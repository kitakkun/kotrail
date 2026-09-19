@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.PreviewScope
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.compose.isPreview
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.analysis.collectors.AbstractDiagnosticCollector
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassLikeSymbol
import org.jetbrains.kotlin.fir.declarations.utils.isActual
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
 * `Unit`-returning ones whose visibility falls under `compose.previewRequired.scope`; preview
 * functions, `override` / `expect` / `actual` functions, functions without a body, and local
 * functions are left alone.
 *
 * As a file checker this runs with the file's checker context, so a `@Suppress` on the composable
 * itself or on a class around it is not in `suppressedDiagnostics`; those are read here from the
 * declarations directly, the way the compiler reads them for its own diagnostics.
 */
object ComposablePreviewRequiredChecker : FirFileChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_PREVIEW_REQUIRED)) return
        val session = context.session

        val functions = mutableListOf<Collected>()
        collectFunctions(declaration.declarations, emptySet(), functions)
        val previewed = mutableSetOf<FirNamedFunctionSymbol>()
        for ((function, _) in functions) {
            if (!function.isPreview(session)) continue
            val collector = CalleeCollector()
            function.body?.accept(collector)
            previewed += collector.callees
        }

        val diagnostic = KotrailDiagnostics.COMPOSABLE_WITHOUT_PREVIEW
        val severity = config.severity(diagnostic.rule)
        for ((function, suppressed) in functions) {
            if (!function.needsPreview(session, config.compose.previewRequireFor)) continue
            if (function.symbol in previewed) continue
            if (diagnostic.baseName in suppressed || severity.suppressAllName in suppressed) continue
            val source = function.source ?: continue
            if (source.kind is KtFakeSourceElementKind) continue
            reportKotrail(source, diagnostic, function.name.asString())
        }
    }

    /** A function together with the diagnostic names suppressed on it or on any class around it. */
    private data class Collected(val function: FirNamedFunction, val suppressed: Set<String>)

    /** Top-level functions and members of classes and objects, at any nesting depth. */
    private fun collectFunctions(declarations: List<FirDeclaration>, suppressed: Set<String>, into: MutableList<Collected>) {
        for (declaration in declarations) {
            when (declaration) {
                is FirNamedFunction -> into += Collected(declaration, suppressed + declaration.suppressedNames())
                is FirRegularClass -> collectFunctions(declaration.declarations, suppressed + declaration.suppressedNames(), into)
                else -> {}
            }
        }
    }

    private fun FirDeclaration.suppressedNames(): Set<String> =
        AbstractDiagnosticCollector.getDiagnosticsSuppressedForContainer(this)?.toSet().orEmpty()

    private val Severity.suppressAllName: String
        get() = if (this == Severity.WARNING) AbstractDiagnosticCollector.SUPPRESS_ALL_WARNINGS else AbstractDiagnosticCollector.SUPPRESS_ALL_ERRORS

    private fun FirNamedFunction.needsPreview(session: FirSession, scope: PreviewScope): Boolean {
        if (!symbol.isComposable(session)) return false
        if (!returnTypeRef.coneType.isUnit) return false
        if (isOverride || isExpect || isActual || isPreview(session)) return false
        // An abstract member has nothing to render; its implementations are what previews call.
        if (body == null) return false
        return when (scope) {
            PreviewScope.PUBLIC -> visibility == Visibilities.Public
            PreviewScope.INTERNAL -> visibility == Visibilities.Public || visibility == Visibilities.Internal
            PreviewScope.ALL -> visibility != Visibilities.Local
        }
    }

    private fun FirNamedFunction.isPreview(session: FirSession): Boolean = symbol.isPreview(session)

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

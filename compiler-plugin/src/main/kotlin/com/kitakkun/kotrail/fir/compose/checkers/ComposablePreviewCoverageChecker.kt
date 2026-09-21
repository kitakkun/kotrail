@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.PreviewScope
import com.kitakkun.kotrail.exclude.Glob
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.emitsUi
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.compose.isPreview
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
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import java.util.WeakHashMap

/**
 * Checks, from the compilation that holds the previews, that every UI composable of the listed
 * packages is called by some `@Preview` function in this compilation:
 *
 * ```yaml
 * rules:
 *   compose:
 *     previewCoverage:
 *       packages: [com.acme.ui, com.acme.ui.cards]
 * ```
 *
 * The composables live on the classpath (a library the sample module depends on, `main` seen from
 * a screenshot-test source set) or among this compilation's own files; either way they are
 * enumerated by package through the symbol provider, so a package is named exactly rather than
 * by pattern. Previews from every file of the compilation count. What is missing is reported
 * on the package directive of one file, the first by name among those that contain previews, so
 * that a compilation without any preview still fails at a definite place.
 *
 * Off by default: it is the library and screenshot-test counterpart of
 * [ComposablePreviewRequiredChecker], enabled only in the compilation that carries previews.
 */
object ComposablePreviewCoverageChecker : FirFileChecker(MppCheckerKind.Common) {
    private val indexes = WeakHashMap<FirSession, Index>()

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_PREVIEW_COVERAGE)) return
        val settings = config.compose.previewCoverage
        if (settings.packages.isEmpty()) return
        val session = context.session
        val index = synchronized(indexes) { indexes.getOrPut(session) { Index(session) } }
        if (index.anchorName != declaration.name) return

        val excluded = settings.excludeNames.map(::Glob)
        val missing = settings.packages
            .flatMap { session.composablesIn(FqName(it), settings.visibility) }
            .filter { it.callableId !in index.previewed }
            .filter { symbol -> excluded.none { it.matches(symbol.callableId.asSingleFqName().asString()) } }
            .sortedBy { it.callableId.asSingleFqName().asString() }
        if (missing.isEmpty()) return

        val source = declaration.packageDirective.source ?: declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        for (symbol in missing) {
            reportKotrail(source, KotrailDiagnostics.COMPOSABLE_NOT_COVERED_BY_PREVIEW, symbol.callableId.asSingleFqName().asString())
        }
    }

    /** The public (or also internal) top-level UI composables declared in [packageName], from source or classpath. */
    private fun FirSession.composablesIn(packageName: FqName, scope: PreviewScope): List<FirNamedFunctionSymbol> {
        val names = symbolProvider.symbolNamesProvider.getTopLevelCallableNamesInPackage(packageName) ?: return emptyList()
        return names.flatMap { name -> symbolProvider.getTopLevelFunctionSymbols(packageName, name) }.filter { symbol ->
            symbol.isComposable(this) &&
                symbol.resolvedReturnType.isUnit &&
                !symbol.isExpect &&
                !symbol.isPreview(this) &&
                symbol.emitsUi(this, kotrailConfig.compose.nonUiPackages) &&
                when (scope) {
                    PreviewScope.PUBLIC -> symbol.visibility == Visibilities.Public
                    PreviewScope.INTERNAL, PreviewScope.ALL -> symbol.visibility == Visibilities.Public || symbol.visibility == Visibilities.Internal
                }
        }
    }

    /** What the compilation's previews call, and which file carries the report; computed once per session. */
    private class Index(session: FirSession) {
        val previewed: Set<CallableId>
        val anchorName: String?

        init {
            val provider = session.firProvider
            val packages = provider.symbolProvider.symbolNamesProvider.getPackageNames().orEmpty()
            val files = packages.flatMap { provider.getFirFilesByPackage(FqName(it)) }.distinct().sortedBy { it.name }
            val callees = mutableSetOf<CallableId>()
            var anchor: String? = null
            for (file in files) {
                val functions = mutableListOf<FirNamedFunction>()
                collectFunctions(file.declarations, functions)
                var hasPreview = false
                for (function in functions) {
                    if (!function.symbol.isPreview(session)) continue
                    hasPreview = true
                    val collector = CalleeCollector()
                    function.body?.accept(collector)
                    callees += collector.callees
                }
                if (hasPreview && anchor == null) anchor = file.name
            }
            previewed = callees
            anchorName = anchor ?: files.firstOrNull()?.name
        }

        private fun collectFunctions(declarations: List<FirDeclaration>, into: MutableList<FirNamedFunction>) {
            for (declaration in declarations) {
                when (declaration) {
                    is FirNamedFunction -> into += declaration
                    is FirRegularClass -> collectFunctions(declaration.declarations, into)
                    else -> {}
                }
            }
        }
    }

    private class CalleeCollector : FirVisitorVoid() {
        val callees = mutableSetOf<CallableId>()

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            functionCall.calleeReference.toResolvedNamedFunctionSymbol()?.callableId?.let { callees += it }
            functionCall.acceptChildren(this)
        }
    }
}

@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.PreviewScope
import com.kitakkun.kotrail.exclude.Glob
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.ComposableManifest
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
import org.jetbrains.kotlin.fir.resolve.providers.impl.FirCompositeSymbolProvider
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
 *   compose.previewCoverage:
 *     packages: [com.acme.ui, com.acme.ui.cards]
 * ```
 *
 * The composables are among this compilation's own files, in a module compiled from source in
 * the same build, or recorded by an associated compilation (`main` seen from a `preview` or
 * screenshot-test compilation reaches it as class files only, which no symbol provider
 * enumerates, so `main` writes what it declares; see [ComposableManifest]). Either way they are
 * found by package, so a package is named exactly rather than by pattern; a package in which
 * nothing is found is reported as such rather than counted as covered. Previews from every file
 * of the compilation count: those of the files being compiled from their bodies, and those of
 * the files an incremental build left alone from the records the last build that saw them wrote
 * (see [ComposableManifest]). What is missing is reported on the package directive of one file,
 * the first by name among those that contain previews, so that a compilation without any
 * preview still fails at a definite place.
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

        val source = declaration.packageDirective.source ?: declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val excluded = settings.excludeNames.map(::Glob)
        val previewed = index.previewed
        for (packageName in settings.packages) {
            val composables = session.composablesIn(FqName(packageName), settings.visibility).map { it.callableId.asSingleFqName().asString() } +
                index.recorded.filter { it.packageName == packageName && it.counts(settings.visibility) }.map { it.name }
            // A package with nothing to cover is a typo, or one this compilation cannot see; silence would read as full coverage.
            if (composables.isEmpty()) {
                reportKotrail(source, KotrailDiagnostics.PREVIEW_COVERAGE_PACKAGE_EMPTY, packageName)
                continue
            }
            val missing = composables.distinct()
                .filter { it !in previewed }
                .filter { name -> excluded.none { it.matches(name) } }
                .sorted()
            for (name in missing) {
                reportKotrail(source, KotrailDiagnostics.COMPOSABLE_NOT_COVERED_BY_PREVIEW, name)
            }
        }
    }

    private fun ComposableManifest.Entry.counts(scope: PreviewScope): Boolean = when (scope) {
        PreviewScope.PUBLIC -> visibility == "public"
        PreviewScope.INTERNAL, PreviewScope.ALL -> visibility == "public" || visibility == "internal"
    }

    /**
     * The public (or also internal) top-level UI composables declared in [packageName], from
     * source or classpath. The names are asked of each symbol provider separately: the composite
     * provider answers null as soon as one of its parts cannot enumerate (class files cannot), which
     * on the JVM is always.
     */
    private fun FirSession.composablesIn(packageName: FqName, scope: PreviewScope): List<FirNamedFunctionSymbol> {
        val providers = (symbolProvider as? FirCompositeSymbolProvider)?.providers ?: listOf(symbolProvider)
        val names = providers.flatMapTo(linkedSetOf()) { it.symbolNamesProvider.getTopLevelCallableNamesInPackage(packageName).orEmpty() }
        return names.flatMap { name -> symbolProvider.getTopLevelFunctionSymbols(packageName, name) }.distinct().filter { symbol ->
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

    /**
     * What the compilation's previews call, which file carries the report, and the composables the
     * associated compilations recorded (see [ComposableManifest]); computed once per session.
     */
    private class Index(session: FirSession) {
        val previewed: Set<String>
        val anchorName: String?
        val recorded: List<ComposableManifest.Entry> = ComposableManifest.read(session.kotrailConfig.associatedComposablesDirs)

        init {
            val provider = session.firProvider
            val packages = provider.symbolProvider.symbolNamesProvider.getPackageNames().orEmpty()
            val files = packages.flatMap { provider.getFirFilesByPackage(FqName(it)) }.distinct().sortedBy { it.name }
            val callees = mutableSetOf<String>()
            var anchor: String? = null
            for (file in files) {
                val functions = mutableListOf<FirNamedFunction>()
                collectFunctions(file.declarations, functions)
                var hasPreview = false
                for (function in functions) {
                    if (!function.symbol.isPreview(session)) continue
                    hasPreview = true
                    callees += calleesOf(function).map { it.asSingleFqName().asString() }
                }
                if (hasPreview && anchor == null) anchor = file.name
            }
            // An incremental build compiles a subset of the files; the previews of the rest are in their records.
            val compiled = files.mapNotNullTo(HashSet()) { it.sourceFile?.path }
            for ((path, recordedCallees) in ComposableManifest.readPreviewCallees(session.kotrailConfig.composablesDir)) {
                if (path !in compiled) callees += recordedCallees
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

    /** The functions a preview's body calls, by callable id. */
    fun calleesOf(preview: FirNamedFunction): Set<CallableId> {
        val collector = CalleeCollector()
        preview.body?.accept(collector)
        return collector.callees
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

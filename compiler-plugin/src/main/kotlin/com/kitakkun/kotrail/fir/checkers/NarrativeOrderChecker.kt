package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.FixEdit
import com.kitakkun.kotrail.fir.HelperDistance
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.fix.FixBuilder
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.reportingAt
import com.kitakkun.kotrail.fir.test.isTestSourcePath
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.analysis.collectors.AbstractDiagnosticCollector
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol

/**
 * Keeps a file readable from the top down, the story first and the details after: a private
 * helper comes after the code that calls it, and close to it. See `docs/rules/narrative-order.md`.
 *
 * ```kotlin
 * private fun parseHeader(bytes: ByteArray): Header { ... }     // reported: load() is below and calls it
 * fun load(bytes: ByteArray): Document {
 *     val header = parseHeader(bytes)
 *     ...
 * }
 * ```
 *
 * The order check (`KOTRAIL_HELPER_BEFORE_FIRST_USE`) looks at every container, the file and
 * every class: a private function's callers are all in the same container, so the order is
 * decidable, and the function must come after the earliest declaration (function, property
 * initializer, `init` block) that calls or references it. A function that nothing in the
 * container uses, or that is in mutual recursion with its first caller, is left alone. The fix
 * moves the whole declaration to just after its first caller, with the private functions that
 * only it calls when the move would leave them above their caller or far from it
 * ([HelperDistance.movedTogether]).
 *
 * The distance check ([HelperDistanceChecker]) reports a helper that, in the right order, still
 * sits far from its only caller; `maxCallerDistance: 0` switches it off, and test source sets
 * are left to the order check alone. Both checks read one walk over the file
 * ([DeclarationUses]). As a file checker this runs with the file's context, so a `@Suppress` on
 * a helper or on a declaration around it is also read from the declarations ([isSuppressedOn]).
 */
object NarrativeOrderChecker : FirFileChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.NARRATIVE_ORDER)) return
        val path = declaration.sourceFile?.path
        if (path != null && config.generated.matchesPath(path)) return
        val uses = DeclarationUses.of(declaration)
        val settings = config.narrativeOrder
        val measured = settings.maxCallerDistance > 0 && path != null && !isTestSourcePath(path)
        val collected by lazy { HelperDistanceChecker.collect(declaration, uses) }
        val maxCallerDistance = if (measured) settings.maxCallerDistance else 0
        for (container in uses.containers) {
            checkOrder(container, uses) { helper, user ->
                collected?.let { HelperDistanceChecker.movedSources(it, listOf(helper), user, before = false, maxCallerDistance, settings.maxHelperNoise) }
            }
        }
        if (measured) collected?.let { HelperDistanceChecker.check(declaration, it, settings) }
    }

    /**
     * Reports, in [container], each private function above the first declaration that uses it.
     * [movedWith] gives the sources its fix moves after that user, given the function and the
     * user, or `null` for the function alone.
     */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkOrder(
        container: DeclarationUses.Container,
        uses: DeclarationUses,
        movedWith: (FirNamedFunctionSymbol, KtSourceElement) -> List<KtSourceElement>?,
    ) {
        val ordered = container.declarations
            .filter { it.source != null && it.source?.kind !is KtFakeSourceElementKind }
            .sortedBy { it.source!!.startOffset }
        val helpers = ordered.filterIsInstance<FirNamedFunction>().filter { it.visibility == Visibilities.Private && it.body != null }
        for (helper in helpers) {
            val helperSource = helper.source ?: continue
            val firstUser = ordered.firstOrNull { member -> member !== helper && helper.symbol in uses.of(member) } ?: continue
            val userSource = firstUser.source ?: continue
            if (userSource.startOffset < helperSource.startOffset) continue
            // Mutual recursion: the helper uses its first user too, and neither order tells the story.
            if ((firstUser as? FirNamedFunction)?.symbol?.let { it in uses.of(helper) } == true) continue
            val site = container.enclosing + helper
            val diagnostic = KotrailDiagnostics.HELPER_BEFORE_FIRST_USE
            if (isSuppressedOn(site, diagnostic.baseName, diagnostic.rule)) continue
            val userName = (firstUser as? FirNamedFunction)?.name?.asString() ?: "the declaration below"
            val moved = movedWith(helper.symbol, userSource) ?: listOf(helperSource)
            reportingAt(site) {
                reportKotrail(helperSource, diagnostic, helper.name.asString(), userName, moveAfterFix(moved, userSource, container.source))
            }
        }
    }

    /**
     * Deletes the [moved] declarations, the helper first (the lines of each, and one blank line
     * after it), and inserts them in that order after the user, each separated by a blank line and
     * indented alike.
     */
    private fun moveAfterFix(moved: List<KtSourceElement>, user: KtSourceElement, container: KtSourceElement?): List<FixEdit> =
        FixBuilder.over(container)?.moveDeclarationsAfter(moved, user) ?: emptyList()
}

/**
 * Whether a `@Suppress` on one of [declarations] names the diagnostic [baseName], or every error
 * or warning at the severity [rule] is reported at. A file checker runs with the file's context,
 * which holds the file's own suppressions only. The compiler still drops a diagnostic that a
 * `@Suppress` around it names once it reaches that declaration, but only by the factory's name,
 * which carries a `_WARNING` / `_ERROR` suffix at a severity the project moved, and only after
 * the fix is recorded; reading the declarations here settles both.
 */
context(context: CheckerContext)
internal fun isSuppressedOn(declarations: List<FirDeclaration>, baseName: String, rule: KotrailRule): Boolean {
    val all = if (context.session.kotrailConfig.severity(rule) == Severity.WARNING) {
        AbstractDiagnosticCollector.SUPPRESS_ALL_WARNINGS
    } else {
        AbstractDiagnosticCollector.SUPPRESS_ALL_ERRORS
    }
    return declarations.any { declaration ->
        val suppressed = AbstractDiagnosticCollector.getDiagnosticsSuppressedForContainer(declaration).orEmpty()
        baseName in suppressed || all in suppressed
    }
}

@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.FixEdit
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.fix.FixBuilder
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Keeps a file or class readable from the top down: a private function is declared after the
 * first declaration that uses it.
 *
 * ```kotlin
 * private fun parseHeader(bytes: ByteArray): Header { ... }     // reported: load() is below and calls it
 * fun load(bytes: ByteArray): Document {
 *     val header = parseHeader(bytes)
 *     ...
 * }
 * ```
 *
 * Read from the top, the file then tells the story first and the details after, the way a
 * newspaper article does; helpers placed before their callers make the reader hold every
 * detail before learning what it is for. A private function's callers are all in the same
 * container, so the order is decidable: the function must come after the earliest declaration
 * (function, property initializer, or `init`) that calls or references it. A function that
 * nothing in the container uses, or that is in mutual recursion with its first caller, is left
 * alone. The fix moves the whole declaration to just after its first caller.
 */
object NarrativeOrderChecker {
    object ClassChecker : FirRegularClassChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirRegularClass) {
            checkContainer(declaration.declarations, declaration.source)
        }
    }

    object FileChecker : FirFileChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirFile) {
            checkContainer(declaration.declarations, declaration.source)
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkContainer(declarations: List<FirDeclaration>, container: KtSourceElement?) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.NARRATIVE_ORDER)) return
        val ordered = declarations.filter { it.source != null && it.source?.kind !is KtFakeSourceElementKind }.sortedBy { it.source!!.startOffset }
        val helpers = ordered.filterIsInstance<FirNamedFunction>().filter { it.visibility == Visibilities.Private && it.body != null }
        if (helpers.isEmpty()) return

        // Who uses whom, among the container's own declarations.
        val uses = ordered.associateWith { member -> UseCollector().also { member.accept(it) }.used }
        for (helper in helpers) {
            val helperSource = helper.source ?: continue
            val firstUser = ordered.firstOrNull { member -> member !== helper && helper.symbol in uses.getValue(member) } ?: continue
            val userSource = firstUser.source ?: continue
            if (userSource.startOffset < helperSource.startOffset) continue
            // Mutual recursion: the helper uses its first user too, and neither order tells the story.
            if ((firstUser as? FirNamedFunction)?.symbol?.let { it in uses.getValue(helper) } == true) continue
            val userName = (firstUser as? FirNamedFunction)?.name?.asString() ?: "the declaration below"
            reportKotrail(helperSource, KotrailDiagnostics.HELPER_BEFORE_FIRST_USE, helper.name.asString(), userName, moveAfterFix(helperSource, userSource, container))
        }
    }

    /** The named functions a declaration calls or references. */
    private class UseCollector : FirVisitorVoid() {
        val used = mutableSetOf<FirNamedFunctionSymbol>()

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            (functionCall.calleeReference.toResolvedCallableSymbol() as? FirNamedFunctionSymbol)?.let { used += it }
            functionCall.acceptChildren(this)
        }

        override fun visitCallableReferenceAccess(callableReferenceAccess: FirCallableReferenceAccess) {
            (callableReferenceAccess.calleeReference.toResolvedCallableSymbol() as? FirNamedFunctionSymbol)?.let { used += it }
            callableReferenceAccess.acceptChildren(this)
        }
    }

    /** Deletes the helper (its lines, and one blank line after it) and inserts it after the user, separated by a blank line and indented alike. */
    private fun moveAfterFix(helper: KtSourceElement, user: KtSourceElement, container: KtSourceElement?): List<FixEdit> =
        FixBuilder.over(container)?.moveAfter(helper, user) ?: emptyList()
}

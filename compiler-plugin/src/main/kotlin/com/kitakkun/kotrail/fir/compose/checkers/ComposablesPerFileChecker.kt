@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
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
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.name.Name

/**
 * Caps the number of non-private UI composables declared in one file:
 *
 * ```kotlin
 * // Screen.kt with HomeScreen, Header, ItemRow, Footer, EmptyState ... all public
 * ```
 *
 * A file that keeps growing new public components is the file-level version of the deep
 * nesting problem: it hides which component is the unit of reuse. Private helpers and
 * `@Preview` functions do not count, so "one component, its private pieces, its previews" is
 * always fine. Overloads of one name are one component (`Button(text)`, `Button(icon)`) and
 * count once unless `countOverloadsSeparately` is set. Composables past the limit are reported
 * in declaration order, one diagnostic each, so moving them out fixes the file.
 */
object ComposablesPerFileChecker : FirFileChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_COMPOSABLES_PER_FILE)) return
        val limit = config.compose.maxComposablesPerFile
        if (limit <= 0) return
        val session = context.session

        val counted = mutableListOf<FirNamedFunction>()
        collectCounted(declaration.declarations, session, counted)

        // Each function's position among the file's components, in declaration order: its own,
        // or that of the first overload of its name.
        val separately = config.compose.countOverloadsSeparately
        val firstOfName = mutableMapOf<Name, Int>()
        var total = 0
        val positions = counted.map { function ->
            if (separately) total++ else firstOfName.getOrPut(function.name) { total++ }
        }
        if (total <= limit) return

        val description = if (separately) "$total non-private composables" else "$total distinct non-private composables"
        for ((function, position) in counted.zip(positions)) {
            if (position < limit) continue
            val source = function.source ?: continue
            if (source.kind is KtFakeSourceElementKind) continue
            reportKotrail(source, KotrailDiagnostics.TOO_MANY_COMPOSABLES_IN_FILE, description, limit.toString())
        }
    }

    private fun collectCounted(declarations: List<FirDeclaration>, session: FirSession, into: MutableList<FirNamedFunction>) {
        for (declaration in declarations) {
            when (declaration) {
                is FirNamedFunction -> if (declaration.isCountedComposable(session)) into += declaration
                is FirRegularClass -> collectCounted(declaration.declarations, session, into)
                else -> {}
            }
        }
    }

    private fun FirNamedFunction.isCountedComposable(session: FirSession): Boolean {
        if (!symbol.isComposable(session)) return false
        if (!returnTypeRef.coneType.isUnit) return false
        if (visibility == Visibilities.Private || visibility == Visibilities.Local) return false
        if (symbol.hasAnnotation(ComposeNames.PREVIEW, session)) return false
        return symbol.resolvedAnnotationsWithClassIds.none { annotation ->
            annotation.toAnnotationClassLikeSymbol(session)?.hasAnnotation(ComposeNames.PREVIEW, session) == true
        }
    }
}

@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassLikeSymbol
import org.jetbrains.kotlin.fir.declarations.utils.isActual
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.text

/**
 * Caps a file in two ways: its lines of code, and the number of names it declares at the top
 * level.
 *
 * ```kotlin
 * // Utils.kt: 14 top-level functions, 3 data classes and 2 properties, 700 lines
 * ```
 *
 * A file that keeps taking one more helper ends up flat: a long list of declarations with
 * nothing to say which belong together, where the reader has to scan everything to find
 * anything. The lines check (`maxLines`, code lines only: blank, brace-only, comment, `package`
 * and `import` lines do not count) reports once, on the package directive. The names check
 * (`maxTopLevelDeclarations`) counts distinct top-level names: classes, functions, public
 * properties and type aliases; overloads of one function count once. Not counted: `@Preview`
 * functions and private properties, which belong to the component or the file they serve as its
 * fixtures and constants, and `actual` declarations, whose shape the `expect` side fixed. The
 * file gets one summary on its package directive, with the breakdown and the fix that fits (a
 * pile of private composables is promoted to a component in its own file); each name past the
 * limit gets a short diagnostic of its own, in declaration order, so moving them out fixes the file.
 */
object FileLengthChecker : FirFileChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.FILE_LENGTH)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        checkLines(declaration, config.fileLength.maxLines)
        checkNames(declaration, config.fileLength.maxTopLevelDeclarations)
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkLines(file: FirFile, limit: Int) {
        if (limit <= 0) return
        val text = file.source?.text?.toString() ?: return
        val lines = text.split('\n').count { isCode(it) }
        if (lines <= limit) return
        val at = file.packageDirective.source?.takeUnless { it.kind is KtFakeSourceElementKind }
            ?: file.declarations.firstNotNullOfOrNull { it.source } ?: return
        reportKotrail(at, KotrailDiagnostics.FILE_TOO_LONG, "$lines lines of code (limit $limit)")
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkNames(file: FirFile, limit: Int) {
        if (limit <= 0) return
        val session = context.session
        val counted = file.declarations.filter { it.countsAsName(session) }
        val firstOfName = LinkedHashMap<Name, Int>()
        var total = 0
        val positions = counted.map { firstOfName.getOrPut(it.declaredName()) { total++ } }
        if (total <= limit) return

        val distinct = counted.distinctBy { it.declaredName() }
        val kinds = distinct.groupingBy { it.kind() }.eachCount()
        val breakdown = KIND_ORDER.mapNotNull { kind -> kinds[kind]?.let { count -> "$count ${plural(kind, count)}" } }
        val composables = distinct.count { it is FirNamedFunction && it.symbol.isComposable(session) }
        val advice = if (composables * 2 >= distinct.size) {
            "A pile of private composables is a component waiting for a file: promote a group of them to an internal composable of its own."
        } else {
            "Move each to the file of the type it serves, or give a group of them a class, an object or a file of their own."
        }
        val at = file.packageDirective.source?.takeUnless { it.kind is KtFakeSourceElementKind } ?: counted.first().source
        reportKotrail(at, KotrailDiagnostics.FILE_TOO_FLAT, "$total top-level names (${breakdown.joinToString(", ")}), limit $limit", advice)
        for ((declaration, position) in counted.zip(positions)) {
            if (position < limit) continue
            val source = declaration.source ?: continue
            if (source.kind is KtFakeSourceElementKind) continue
            reportKotrail(source, KotrailDiagnostics.TOO_MANY_TOP_LEVEL_DECLARATIONS, "${position + 1} of $total", limit.toString())
        }
    }

    private fun plural(kind: String, count: Int): String = when {
        count == 1 -> kind
        kind == "class" -> "classes"
        kind == "property" -> "properties"
        kind == "type alias" -> "type aliases"
        else -> kind + "s"
    }

    private fun FirDeclaration.countsAsName(session: org.jetbrains.kotlin.fir.FirSession): Boolean {
        if (this is FirMemberDeclaration && isActual) return false
        return when (this) {
            is FirRegularClass, is FirTypeAlias -> true
            is FirProperty -> visibility != Visibilities.Private
            is FirNamedFunction -> !isPreview(session)
            else -> false
        }
    }

    private fun FirNamedFunction.isPreview(session: org.jetbrains.kotlin.fir.FirSession): Boolean =
        symbol.hasAnnotation(ComposeNames.PREVIEW, session) || symbol.resolvedAnnotationsWithClassIds.any { annotation ->
            annotation.toAnnotationClassLikeSymbol(session)?.hasAnnotation(ComposeNames.PREVIEW, session) == true
        }

    private fun FirDeclaration.declaredName(): Name = when (this) {
        is FirRegularClass -> name
        is FirNamedFunction -> name
        is FirProperty -> name
        is FirTypeAlias -> name
        else -> Name.special("<unnamed>")
    }

    private fun FirDeclaration.kind(): String = when (this) {
        is FirRegularClass -> "class"
        is FirNamedFunction -> "function"
        is FirProperty -> "property"
        is FirTypeAlias -> "type alias"
        else -> "declaration"
    }

    private val KIND_ORDER = listOf("class", "function", "property", "type alias")

    /** Lines of code: not blank, not a lone brace, not a comment, not `package` or `import`. */
    private fun isCode(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == "{" || trimmed == "}") return false
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) return false
        if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) return false
        return true
    }
}

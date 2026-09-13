package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.text

/**
 * Limits how many lines of code a function body may have:
 *
 * ```kotlin
 * fun sync() {          // 72 lines of code follow
 *     ...
 * }                     // reported: 72 lines of code (limit 50)
 * ```
 *
 * A long function is where an assistant keeps adding "one more thing", because nothing pushes
 * back. The limit does: past it, the only way to add is to extract, and the extracted piece has
 * to be named.
 *
 * Only lines of code count. Blank lines, lines that hold nothing but a brace, and comment lines
 * (line comments, block comment openers, and their continuation lines) do not, so formatting and
 * documentation never tip a function over. A
 * `@Composable` function has its own limit, since a UI tree runs longer than logic of the same
 * complexity. Either limit at `0` is unlimited.
 */
object FunctionLengthChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.FUNCTION_LENGTH)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val body = declaration.body?.source ?: return

        val limit = if (declaration.symbol.isComposable(context.session)) {
            config.functionLength.maxComposableLines
        } else {
            config.functionLength.maxLines
        }
        if (limit <= 0) return

        val text = source.text?.toString() ?: return
        val start = (body.startOffset - source.startOffset).coerceIn(0, text.length)
        val end = (body.endOffset - source.startOffset).coerceIn(start, text.length)
        val lines = text.substring(start, end).split('\n').count { isCode(it) }
        if (lines <= limit) return

        reportKotrail(source, KotrailDiagnostics.FUNCTION_TOO_LONG, "$lines lines of code (limit $limit)")
    }

    private fun isCode(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == "{" || trimmed == "}") return false
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) return false
        return true
    }
}

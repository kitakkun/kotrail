package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.comments.Comment
import com.kitakkun.kotrail.comments.CommentKind
import com.kitakkun.kotrail.comments.CommentScanner
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.text

/**
 * Limits comment length. Consecutive `//` lines that each start their line form one block;
 * `/* */` comments count their lines; KDoc has its own (by default unlimited) budget.
 *
 * Comments are not part of FIR, so this checker scans the file text itself and reports on the
 * comment's character range through a fake source element derived from the file's source.
 */
object CommentLengthChecker : FirFileChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMMENT_LENGTH)) return
        val settings = config.comments
        val source = declaration.source ?: return
        val text = source.text ?: return

        val comments = CommentScanner.scan(text)
        val anchor = CommentRangeAnchor(source, text, comments)
        var index = 0
        while (index < comments.size) {
            val comment = comments[index]
            when (comment.kind) {
                CommentKind.LINE -> {
                    if (!comment.startsLine) {
                        index++
                        continue
                    }
                    // Group `//` comments on consecutive lines.
                    var last = index
                    while (last + 1 < comments.size && continuesLineBlock(text, comments[last], comments[last + 1])) last++
                    val lines = last - index + 1
                    if (settings.maxLines > 0 && lines > settings.maxLines) {
                        report(anchor, comment.startOffset, comments[last].endOffset,
                            "$lines consecutive comment lines (limit ${settings.maxLines})")
                    }
                    index = last + 1
                }
                CommentKind.BLOCK -> {
                    if (settings.maxLines > 0 && comment.lineCount > settings.maxLines) {
                        report(anchor, comment.startOffset, comment.endOffset,
                            "block comment spans ${comment.lineCount} lines (limit ${settings.maxLines})")
                    }
                    index++
                }
                CommentKind.KDOC -> {
                    if (settings.maxKDocLines > 0 && comment.lineCount > settings.maxKDocLines) {
                        report(anchor, comment.startOffset, comment.endOffset,
                            "KDoc spans ${comment.lineCount} lines (limit ${settings.maxKDocLines})")
                    }
                    index++
                }
            }
        }
    }

    private fun continuesLineBlock(text: CharSequence, previous: Comment, next: Comment): Boolean {
        if (next.kind != CommentKind.LINE || !next.startsLine) return false
        var newlines = 0
        for (k in previous.endOffset until next.startOffset) {
            val c = text[k]
            if (c == '\n') newlines++ else if (!c.isWhitespace()) return false
        }
        return newlines == 1
    }

    context(reporter: DiagnosticReporter, context: CheckerContext)
    private fun report(anchor: CommentRangeAnchor, startOffset: Int, endOffset: Int, description: String) {
        val range = anchor.elementFor(startOffset, endOffset) ?: return
        reportKotrail(range, KotrailDiagnostics.COMMENT_TOO_LONG, description)
    }
}

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.comments.Comment
import com.kitakkun.kotrail.comments.CommentScanner
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.text

/**
 * Reports a comment written inside a function's or constructor's parameter list:
 *
 * ```kotlin
 * fun connect(
 *     host: String,
 *     retries: Int, // how many times to retry before giving up
 * )
 * ```
 *
 * A comment between parameters is invisible where the parameter is used: the IDE's signature
 * help and the generated documentation show KDoc, not this. It is also loosely attached, so a
 * reordering or a removed parameter leaves it describing the wrong one. `@param retries` in
 * the function's KDoc says the same thing where it is read and stays bound to the name.
 *
 * Comments are not part of FIR, so the file text is scanned for them and each is matched
 * against the parameter lists of the file's declared functions, constructors (the primary one
 * included), and anonymous functions written with `fun`. Lambda parameters are left alone, and
 * so is an empty list: its parentheses are found from the parameters' own source ranges, since
 * the plugin keeps clear of the platform's syntax tree classes.
 */
object ParameterCommentChecker : FirFileChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.NO_PARAMETER_COMMENTS)) return
        val source = declaration.source ?: return
        val text = source.text ?: return
        val comments = CommentScanner.scan(text)
        if (comments.isEmpty()) return

        val lists = ParameterListCollector(text, comments).also { declaration.accept(it) }.ranges
        if (lists.isEmpty()) return
        val anchor = CommentRangeAnchor(source, text, comments)
        for (comment in comments) {
            val list = lists.firstOrNull { comment.startOffset >= it.first && comment.endOffset <= it.last } ?: continue
            val range = anchor.elementFor(comment.startOffset, comment.endOffset) ?: continue
            reportKotrail(range, KotrailDiagnostics.COMMENT_IN_PARAMETER_LIST, list.name)
        }
    }

    private class ParameterList(val name: String, val first: Int, val last: Int)

    /**
     * The character ranges of every declared function's parameter list, with the function's name
     * for the message. A list runs from the `(` before its first parameter to the `)` after its
     * last, found by stepping over whitespace, commas, and comments from the parameters' sources.
     */
    private class ParameterListCollector(private val text: CharSequence, private val comments: List<Comment>) : FirVisitorVoid() {
        val ranges = mutableListOf<ParameterList>()
        private val commentEnds = comments.associateBy { it.endOffset }
        private val commentStarts = comments.associateBy { it.startOffset }

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitNamedFunction(namedFunction: FirNamedFunction) {
            collect(namedFunction, namedFunction.name.asString())
            namedFunction.acceptChildren(this)
        }

        override fun visitConstructor(constructor: FirConstructor) {
            collect(constructor, "the constructor")
            constructor.acceptChildren(this)
        }

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
            if (!anonymousFunction.isLambda) collect(anonymousFunction, "the anonymous function")
            anonymousFunction.acceptChildren(this)
        }

        private fun collect(function: FirFunction, name: String) {
            val source = function.source ?: return
            if (source.kind is KtFakeSourceElementKind) return
            val parameters = function.valueParameters.mapNotNull { it.source?.takeUnless { s -> s.kind is KtFakeSourceElementKind } }
            if (parameters.isEmpty()) return
            val open = openingParenthesisBefore(parameters.first().startOffset) ?: return
            val close = closingParenthesisAfter(parameters.last().endOffset) ?: return
            ranges += ParameterList(name, open, close + 1)
        }

        private fun openingParenthesisBefore(offset: Int): Int? {
            var p = offset
            while (p > 0) {
                val c = text[p - 1]
                when {
                    c.isWhitespace() -> p--
                    commentEnds[p] != null -> p = commentEnds.getValue(p).startOffset
                    c == '(' -> return p - 1
                    else -> return null
                }
            }
            return null
        }

        private fun closingParenthesisAfter(offset: Int): Int? {
            var p = offset
            while (p < text.length) {
                val c = text[p]
                when {
                    c.isWhitespace() || c == ',' -> p++
                    commentStarts[p] != null -> p = commentStarts.getValue(p).endOffset
                    c == ')' -> return p
                    else -> return null
                }
            }
            return null
        }
    }
}

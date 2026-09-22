package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.FixEdit
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenBranch
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.text

/**
 * Reports a local `val` that only one branch below it uses:
 *
 * ```kotlin
 * val formatted = format(user)      // reported: only the else branch reads it
 * if (user.isGuest) {
 *     Text("Guest")
 * } else {
 *     Text(formatted)
 * }
 * ```
 *
 * Every declaration a reader passes is one more thing to hold in mind until it is used; a value
 * that matters to one branch belongs in that branch. The rule reports when the value is never
 * used in the declaring block itself and every use sits inside one branch block of an `if`,
 * `when`, or `try` directly below, and only when moving the declaration changes nothing: the
 * initializer is a literal, a read of a variable or property, a string template, or built-in
 * operators over those (a call could run at a different time, or not at all). A use inside a
 * loop body or a lambda is left alone, since the initializer would run more than once or later.
 * The fix moves the declaration to the top of the branch, when the branch has braces.
 */
object NarrowLocalScopeChecker : FirPropertyChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.NARROW_LOCAL_SCOPE)) return
        if (!declaration.isLocal || declaration.isVar || declaration.delegate != null) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val initializer = declaration.initializer ?: return
        if (initializer.source?.kind is KtFakeSourceElementKind) return
        if (!initializer.isPure()) return

        val block = context.containingElements.lastOrNull { it is FirBlock } as? FirBlock ?: return
        val index = block.statements.indexOf(declaration)
        if (index < 0) return
        val later = block.statements.drop(index + 1)

        val finder = UseFinder(declaration.symbol)
        for (statement in later) statement.accept(finder)
        val target = finder.singleBranch() ?: return

        val fix = moveFix(source, block, target)
        reportKotrail(source, KotrailDiagnostics.NARROW_LOCAL_SCOPE, declaration.name.asString(), fix)
    }

    /**
     * Where the uses of a local sit relative to the statements after its declaration: directly
     * in them (which pins the declaration where it is), inside a first-level branch block of an
     * `if` / `when` / `try`, or inside a loop or a lambda (which the declaration must stay
     * outside of). Only uses that all fall into one branch block make a case for moving.
     */
    private class UseFinder(private val target: FirPropertySymbol) : FirVisitorVoid() {
        private var branch: FirBlock? = null
        private val branches = mutableSetOf<FirBlock>()
        private var pinned = false
        private var uses = 0

        fun singleBranch(): FirBlock? = if (!pinned && uses > 0) branches.singleOrNull() else null

        override fun visitElement(element: FirElement) {
            if (pinned) return
            element.acceptChildren(this)
        }

        // FirVisitorVoid routes every node kind to visitElement; the kinds that matter are named here.
        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            if (propertyAccessExpression.calleeReference.toResolvedCallableSymbol() == target) {
                uses++
                val here = branch
                if (here == null) pinned = true else branches += here
            }
            propertyAccessExpression.acceptChildren(this)
        }

        override fun visitWhenExpression(whenExpression: FirWhenExpression) {
            if (pinned) return
            whenExpression.subjectVariable?.accept(this)
            for (whenBranch in whenExpression.branches) {
                whenBranch.condition.accept(this)
                inBranch(whenBranch.result)
            }
        }

        override fun visitWhenBranch(whenBranch: FirWhenBranch) {
            whenBranch.condition.accept(this)
            inBranch(whenBranch.result)
        }

        override fun visitTryExpression(tryExpression: FirTryExpression) {
            if (pinned) return
            inBranch(tryExpression.tryBlock)
            for (catch in tryExpression.catches) inBranch(catch.block)
            tryExpression.finallyBlock?.let { inBranch(it) }
        }

        override fun visitWhileLoop(whileLoop: FirWhileLoop) = outside(whileLoop)

        override fun visitDoWhileLoop(doWhileLoop: FirDoWhileLoop) = outside(doWhileLoop)

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) = outside(anonymousFunction)

        override fun visitNamedFunction(namedFunction: FirNamedFunction) = outside(namedFunction)

        override fun visitRegularClass(regularClass: FirRegularClass) = outside(regularClass)

        override fun visitAnonymousObject(anonymousObject: FirAnonymousObject) = outside(anonymousObject)

        /** A block the declaration could move into: the first one entered counts, nested ones fold into it. */
        private fun inBranch(block: FirBlock) {
            if (pinned) return
            if (branch != null) {
                block.accept(this)
                return
            }
            branch = block
            block.accept(this)
            branch = null
        }

        /** A construct the declaration must stay outside of: any use inside pins it. */
        private fun outside(element: FirElement) {
            if (pinned) return
            val outer = branch
            if (outer != null) {
                // Inside a branch already: the loop or lambda is part of that branch.
                element.acceptChildren(this)
                return
            }
            val probe = UseFinder(target)
            element.acceptChildren(probe)
            if (probe.uses > 0) pinned = true
        }
    }

    /** An initializer that can move without changing when it runs or what it yields. */
    private fun FirExpression.isPure(): Boolean = when (this) {
        is FirLiteralExpression, is FirResolvedQualifier, is FirThisReceiverExpression -> true
        is FirSmartCastExpression -> originalExpression.isPure()
        is FirStringConcatenationCall -> arguments.all { it.isPure() }
        is FirElvisExpression -> lhs.isPure() && rhs.isPure()
        is FirFunctionCall -> {
            val callee = calleeReference.toResolvedNamedFunctionSymbol()
            callee != null && callee.callableId?.classId?.let { it in StandardClassIds.primitiveTypes || it == StandardClassIds.String } == true &&
                (explicitReceiver?.isPure() ?: true) && arguments.all { it.isPure() }
        }
        is FirQualifiedAccessExpression -> explicitReceiver?.isPure() ?: true
        else -> false
    }

    /** Deletes the declaration's line and inserts the declaration before the branch's first statement, when the branch has braces. */
    private fun moveFix(declaration: KtSourceElement, block: FirBlock, target: FirBlock): List<FixEdit> {
        val blockSource = block.source ?: return emptyList()
        val blockText = blockSource.text?.toString() ?: return emptyList()
        val targetSource = target.source ?: return emptyList()
        val targetText = targetSource.text?.toString() ?: return emptyList()
        if (!targetText.trimStart().startsWith("{")) return emptyList()
        val first = target.statements.firstOrNull()?.source ?: return emptyList()

        val declarationText = declaration.text?.toString() ?: return emptyList()
        var lineStart = declaration.startOffset - blockSource.startOffset
        while (lineStart > 0 && blockText[lineStart - 1] != '\n' && blockText[lineStart - 1].isWhitespace()) lineStart--
        var lineEnd = declaration.endOffset - blockSource.startOffset
        if (blockText.getOrNull(lineEnd) == '\n') lineEnd++
        val deletion = FixEdit(blockSource.startOffset + lineStart, blockSource.startOffset + lineEnd, "")

        var indentStart = first.startOffset - blockSource.startOffset
        while (indentStart > 0 && blockText[indentStart - 1] != '\n' && blockText[indentStart - 1].isWhitespace()) indentStart--
        val indent = blockText.substring(indentStart, first.startOffset - blockSource.startOffset)
        val insertion = FixEdit(first.startOffset, first.startOffset, "$declarationText\n$indent")
        return listOf(deletion, insertion)
    }
}

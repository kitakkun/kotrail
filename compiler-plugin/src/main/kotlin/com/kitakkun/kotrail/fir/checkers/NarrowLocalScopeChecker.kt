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
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
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
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenBranch
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
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
 * operators over those (a call could run at a different time, or not at all), and no statement
 * the declaration would move past assigns a `var` the initializer reads. A use inside a loop
 * body or a lambda is left alone, since the initializer would run more than once or later.
 * The fix moves the declaration to the top of the branch, when the branch has braces.
 *
 * The second shape is distance: a local whose first use is more than `maxDistance` lines below
 * its declaration, with unrelated statements in between. The same purity condition applies, and
 * the fix moves the declaration to just before the statement that first uses it.
 */
object NarrowLocalScopeChecker : FirPropertyChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.NARROW_LOCAL_SCOPE)) return
        if (!declaration.isLocal || declaration.isVar || declaration.delegate != null) return
        // `<destruct>` of `val (a, b) = pair`: its entries follow as statements of their own, inside its source range.
        if (declaration.name.isSpecial) return
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
        val dependencies = initializer.varDependencies()
        // A var the function captures in a lambda can change through any call in between.
        if (dependencies.isNotEmpty() && later.any { it.capturesAssignmentOf(dependencies) }) return

        val target = finder.singleBranch()
        if (target != null) {
            if (later.any { it.assignsAnyOf(dependencies, except = target) }) return
            val fix = moveFix(source, block, target)
            reportKotrail(source, KotrailDiagnostics.NARROW_LOCAL_SCOPE, declaration.name.asString(), fix)
            return
        }

        val maxDistance = context.session.kotrailConfig.narrowLocalScope.maxDistance
        if (maxDistance <= 0) return
        val firstUse = later.firstOrNull { statement -> UseFinder(declaration.symbol).also { statement.accept(it) }.let { it.uses > 0 || it.pinned } } ?: return
        if (later.takeWhile { it !== firstUse }.any { it.assignsAnyOf(dependencies, except = null) }) return
        val firstUseSource = firstUse.source ?: return
        val blockSource = block.source ?: return
        val blockText = blockSource.text?.toString() ?: return
        if (firstUseSource.startOffset < source.endOffset) return
        val lines = blockText.substring(source.endOffset - blockSource.startOffset, firstUseSource.startOffset - blockSource.startOffset).count { it == '\n' }
        if (lines <= maxDistance) return
        val fix = moveBeforeFix(source, block, firstUseSource)
        reportKotrail(source, KotrailDiagnostics.LOCAL_DECLARED_TOO_EARLY, declaration.name.asString(), "$lines lines (limit $maxDistance)", fix)
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
        var pinned = false
            private set
        var uses = 0
            private set

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
            // A use anywhere inside, including a lambda nested in this one, which pins the probe without counting.
            if (probe.uses > 0 || probe.pinned) pinned = true
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
        // A read is movable; whether it still reads the same value is checked against the statements moved past.
        is FirQualifiedAccessExpression -> explicitReceiver?.isPure() ?: true
        else -> false
    }

    /** The `var`s the initializer reads: the values that a statement moved past could change. */
    private fun FirExpression.varDependencies(): Set<FirVariableSymbol<*>> {
        val found = mutableSetOf<FirVariableSymbol<*>>()
        accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) = element.acceptChildren(this)
            override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
                (propertyAccessExpression.calleeReference.toResolvedCallableSymbol() as? FirVariableSymbol<*>)?.takeIf { it.isVar }?.let { found += it }
                propertyAccessExpression.acceptChildren(this)
            }
        })
        return found
    }

    /**
     * Whether any of [targets] is assigned inside [element], with [except] (the block the
     * declaration moves into) left out. An assignment inside a lambda counts wherever the lambda
     * is: it can run from any statement in between.
     */
    private fun FirElement.assignsAnyOf(targets: Set<FirVariableSymbol<*>>, except: FirBlock?): Boolean {
        if (targets.isEmpty()) return false
        var found = false
        accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (found || element === except) return
                element.acceptChildren(this)
            }
            override fun visitVariableAssignment(variableAssignment: FirVariableAssignment) {
                val lValue = when (val value = variableAssignment.lValue) {
                    is FirDesugaredAssignmentValueReferenceExpression -> value.expressionRef.value
                    else -> value
                }
                if ((lValue as? FirQualifiedAccessExpression)?.calleeReference?.toResolvedCallableSymbol() in targets) found = true
                else variableAssignment.acceptChildren(this)
            }
        })
        return found
    }

    /** Whether a lambda or local function in [this] assigns one of [targets]: such a var can change through any call. */
    private fun FirElement.capturesAssignmentOf(targets: Set<FirVariableSymbol<*>>): Boolean {
        var found = false
        accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (found) return
                element.acceptChildren(this)
            }
            override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
                if (anonymousFunction.assignsAnyOf(targets, except = null)) found = true
            }
            override fun visitNamedFunction(namedFunction: FirNamedFunction) {
                if (namedFunction.assignsAnyOf(targets, except = null)) found = true
            }
        })
        return found
    }

    /** Deletes the declaration's line and inserts the declaration, with the same indentation, before [statement] of the same block. */
    private fun moveBeforeFix(declaration: KtSourceElement, block: FirBlock, statement: KtSourceElement): List<FixEdit> {
        val blockSource = block.source ?: return emptyList()
        val blockText = blockSource.text?.toString() ?: return emptyList()
        val declarationText = declaration.text?.toString() ?: return emptyList()
        var lineStart = declaration.startOffset - blockSource.startOffset
        while (lineStart > 0 && blockText[lineStart - 1] != '\n' && blockText[lineStart - 1].isWhitespace()) lineStart--
        var lineEnd = declaration.endOffset - blockSource.startOffset
        if (blockText.getOrNull(lineEnd) == '\n') lineEnd++
        val deletion = FixEdit(blockSource.startOffset + lineStart, blockSource.startOffset + lineEnd, "")
        var indentStart = statement.startOffset - blockSource.startOffset
        while (indentStart > 0 && blockText[indentStart - 1] != '\n' && blockText[indentStart - 1].isWhitespace()) indentStart--
        val indent = blockText.substring(indentStart, statement.startOffset - blockSource.startOffset)
        val insertion = FixEdit(statement.startOffset, statement.startOffset, "$declarationText\n$indent")
        return listOf(deletion, insertion)
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

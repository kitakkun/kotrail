package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirCatch
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.text

/**
 * Caps how many variables a reader has to hold in mind at any point of a function:
 *
 * ```kotlin
 * fun summarize(orders: List<Order>, rate: Double, locale: Locale): String {
 *     val total = orders.sumOf { it.amount }
 *     val taxed = total * rate
 *     val count = orders.size
 *     val first = orders.first().placedAt
 *     val last = orders.last().placedAt
 *     val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", locale)
 *     return "$count orders, $taxed, ${formatter.format(first)}..${formatter.format(last)}"   // reported: 9 live
 * }
 * ```
 *
 * A variable is live at a statement when it was declared before it and is still read at or
 * after it; inside a loop, everything the loop reads is live throughout. Function length and
 * complexity are stand-ins for this number; the number is what the reader pays. The first
 * statement past the limit is reported, with the names that are live there, so that extracting
 * a few of them into a function, or narrowing their scope, is a concrete next step.
 * Parameters count, since they are carried too; `this` does not. A variable is not live inside
 * its own initializer, and not past the block, lambda, or catch clause that declares it.
 */
object LiveVariableBudgetChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.LIVE_VARIABLE_BUDGET)) return
        val limit = config.liveVariables.max
        if (limit <= 0) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val body = declaration.body ?: return

        val index = Index()
        declaration.valueParameters.forEach { parameter ->
            val parameterSource = parameter.source
            index.declare(parameter.symbol, parameter.name.asString(), parameterSource?.startOffset ?: source.startOffset, parameterSource?.endOffset ?: source.startOffset, source.endOffset)
        }
        body.accept(index)

        for (statement in index.statements) {
            val statementSource = statement.source ?: continue
            if (statementSource.kind.isSynthetic()) continue
            // The loop variable of a `for` is a statement of the desugared loop; its source is the parameter, not a line.
            if (statement is FirProperty && !statementSource.text.startsWithKeyword()) continue
            val live = index.liveAt(statementSource.startOffset, statementSource.endOffset)
            if (live.size <= limit) continue
            reportKotrail(statementSource, KotrailDiagnostics.TOO_MANY_LIVE_VARIABLES, live.joinToString(), "${live.size} (limit $limit)")
            return
        }
    }

    /** A fake source that is not one of the assignments written as `x += 1` or `x++`, which are statements of their own. */
    private fun KtSourceElementKind.isSynthetic(): Boolean =
        this is KtFakeSourceElementKind &&
            this !is KtFakeSourceElementKind.DesugaredAugmentedAssign &&
            this !is KtFakeSourceElementKind.DesugaredIncrementOrDecrement

    private val declarationKeyword = Regex("^\\s*(?:[\\w@:.]+\\s+)*(?:val|var)\\b")

    private fun CharSequence?.startsWithKeyword(): Boolean = this != null && declarationKeyword.containsMatchIn(this)

    /**
     * Every variable of the function with where it is declared, where its scope ends, and its
     * last read; every statement; and the loops.
     */
    private class Index : FirVisitorVoid() {
        private class Variable(val name: String, val declaredAt: Int, val declarationEnd: Int, val scopeEnd: Int) {
            var lastReadAt: Int = -1
            val readsInLoops = mutableSetOf<IntRange>()
        }

        private val variables = linkedMapOf<FirBasedSymbol<*>, Variable>()
        val statements = mutableListOf<FirStatement>()
        private val loops = mutableListOf<IntRange>()
        private var loopDepth = 0
        private val scopes = mutableListOf<Int>()

        /**
         * Records a variable: declared over [at] to [declarationEnd] (its own initializer is not a
         * place where it is live yet), and gone after [scopeEnd] (the block, lambda, or catch clause
         * that holds it).
         */
        fun declare(symbol: FirBasedSymbol<*>, name: String, at: Int, declarationEnd: Int, scopeEnd: Int) {
            variables[symbol] = Variable(name, at, declarationEnd, scopeEnd)
        }

        /** Names live at a statement spanning [start] to [end], in declaration order. */
        fun liveAt(start: Int, end: Int): List<String> = variables.values
            .filter { variable ->
                variable.declarationEnd <= start && start < variable.scopeEnd &&
                    (variable.lastReadAt >= start || variable.readsInLoops.any { loop -> start in loop })
            }
            .sortedBy { it.declaredAt }
            .map { it.name }

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitBlock(block: FirBlock) {
            val end = block.source?.endOffset
            if (end != null) scopes += end
            for (statement in block.statements) {
                // A nested block (the body of a `for` loop) is checked through its own statements.
                if (statement !is FirBlock) statements += statement
                statement.accept(this)
            }
            if (end != null) scopes.removeAt(scopes.lastIndex)
        }

        override fun visitProperty(property: FirProperty) {
            // `<iterator>` of a `for`, `<destruct>` of a destructuring: names a reader never sees.
            if (property.isLocal && !property.name.isSpecial) {
                val source = property.source
                declare(property.symbol, property.name.asString(), source?.startOffset ?: 0, source?.endOffset ?: 0, scopes.lastOrNull() ?: Int.MAX_VALUE)
            }
            property.acceptChildren(this)
        }

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
            val lambdaSource = anonymousFunction.source
            anonymousFunction.valueParameters.forEach { parameter ->
                val source = parameter.source ?: lambdaSource
                declare(parameter.symbol, parameter.name.asString(), source?.startOffset ?: 0, source?.endOffset ?: 0, lambdaSource?.endOffset ?: Int.MAX_VALUE)
            }
            anonymousFunction.acceptChildren(this)
        }

        override fun visitCatch(catch: FirCatch) {
            val parameter = catch.parameter
            val source = parameter.source
            declare(parameter.symbol, parameter.name.asString(), source?.startOffset ?: 0, source?.endOffset ?: 0, catch.block.source?.endOffset ?: Int.MAX_VALUE)
            catch.block.accept(this)
        }

        override fun visitValueParameter(valueParameter: FirValueParameter) {
            valueParameter.acceptChildren(this)
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            val variable = propertyAccessExpression.calleeReference.toResolvedCallableSymbol()?.let { variables[it] }
            val at = propertyAccessExpression.source?.endOffset
            if (variable != null && at != null) {
                if (at > variable.lastReadAt) variable.lastReadAt = at
                loops.takeLast(loopDepth).forEach { variable.readsInLoops += it }
            }
            propertyAccessExpression.acceptChildren(this)
        }

        override fun visitWhileLoop(whileLoop: FirWhileLoop) = inLoop(whileLoop)

        override fun visitDoWhileLoop(doWhileLoop: FirDoWhileLoop) = inLoop(doWhileLoop)

        private fun inLoop(loop: FirElement) {
            val range = loop.source?.let { it.startOffset..it.endOffset } ?: return loop.acceptChildren(this)
            loops += range
            loopDepth++
            loop.acceptChildren(this)
            loopDepth--
            loops.removeAt(loops.lastIndex)
        }
    }
}

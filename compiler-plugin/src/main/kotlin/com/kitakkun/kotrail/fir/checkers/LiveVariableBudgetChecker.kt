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
 * Parameters count, since they are carried too; `this` does not.
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
        declaration.valueParameters.forEach { index.declare(it.symbol, it.name.asString(), it.source?.startOffset ?: source.startOffset) }
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

    /** Every variable of the function with its declaration offset and its last read, every statement, and the loops. */
    private class Index : FirVisitorVoid() {
        private class Variable(val name: String, val declaredAt: Int) {
            var lastReadAt: Int = -1
            val readsInLoops = mutableSetOf<IntRange>()
        }

        private val variables = linkedMapOf<FirBasedSymbol<*>, Variable>()
        val statements = mutableListOf<FirStatement>()
        private val loops = mutableListOf<IntRange>()
        private var loopDepth = 0

        fun declare(symbol: FirBasedSymbol<*>, name: String, at: Int) {
            variables[symbol] = Variable(name, at)
        }

        /** Names live at a statement spanning [start] to [end], in declaration order. */
        fun liveAt(start: Int, end: Int): List<String> = variables.values
            .filter { variable ->
                variable.declaredAt < start &&
                    (variable.lastReadAt >= start || variable.readsInLoops.any { loop -> start in loop })
            }
            .map { it.name }

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitBlock(block: FirBlock) {
            for (statement in block.statements) {
                // A nested block (the body of a `for` loop) is checked through its own statements.
                if (statement !is FirBlock) statements += statement
                statement.accept(this)
            }
        }

        override fun visitProperty(property: FirProperty) {
            // `<iterator>` of a `for`, `<destruct>` of a destructuring: names a reader never sees.
            if (property.isLocal && !property.name.isSpecial) declare(property.symbol, property.name.asString(), property.source?.startOffset ?: 0)
            property.acceptChildren(this)
        }

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
            anonymousFunction.valueParameters.forEach { parameter ->
                declare(parameter.symbol, parameter.name.asString(), parameter.source?.startOffset ?: anonymousFunction.source?.startOffset ?: 0)
            }
            anonymousFunction.acceptChildren(this)
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

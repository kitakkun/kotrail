package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirBlockChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.utils.isConst
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.isBoolean
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.text

/**
 * Reports a loop over a handful of literals whose body branches on the element, and a loop over
 * data with a literal sentinel joined in for the same purpose:
 *
 * ```kotlin
 * listOf(false, true).forEach { all ->                           // reported: two calls folded into one
 *     FilterChip(text = if (all) "All devices" else "This device", selected = all == allDevices)
 * }
 * (listOf(null) + days).forEach { day ->                         // reported: "All dates" smuggled into the list
 *     FilterChip(text = day ?: "All dates", selected = day == selected)
 * }
 * days.forEach { day -> FilterChip(text = day, selected = day == selected) }   // fine: data
 * listOf("a", "b").forEach(::register)                           // fine: no branch on the element
 * ```
 *
 * A literal collection is not data the code receives; it is the author's own list of cases,
 * and a body that branches on the element is those cases written once each after all, only
 * folded so that the reader has to unfold them. The explicit calls are as short and say what
 * they do. The same goes for `listOf(null) + items`: the sentinel's call written out, then the
 * data looped, reads as two things because it is two things. A literal list of more than
 * `maxElements` is a table and is left alone, as is any body that does not branch on the element.
 */
object NoLiteralLoopChecker {
    private val LOOP_FUNCTIONS = setOf(
        "kotlin.collections.forEach", "kotlin.collections.forEachIndexed", "kotlin.collections.map", "kotlin.collections.mapIndexed",
        "kotlin.collections.mapNotNull", "kotlin.collections.flatMap", "kotlin.collections.onEach", "kotlin.sequences.forEach", "kotlin.sequences.map",
    )
    private val LITERAL_COLLECTIONS = setOf(
        "kotlin.collections.listOf", "kotlin.collections.setOf", "kotlin.collections.mutableListOf", "kotlin.collections.arrayListOf",
        "kotlin.collections.listOfNotNull", "kotlin.collections.setOfNotNull", "kotlin.arrayOf", "kotlin.sequences.sequenceOf",
        "kotlin.collections.linkedSetOf", "kotlin.collections.mutableSetOf",
    )
    private const val PLUS = "kotlin.collections.plus"

    /** `listOf(...).forEach { }`, `.map { }` and their kin. */
    object Calls : FirFunctionCallChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirFunctionCall) {
            val config = context.session.kotrailConfig
            if (!config.isEnabled(KotrailRule.NO_LITERAL_LOOP)) return
            val source = expression.source ?: return
            if (source.kind is KtFakeSourceElementKind) return
            val callee = expression.calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString() ?: return
            if (callee !in LOOP_FUNCTIONS) return
            val subject = expression.explicitReceiver ?: return
            val lambda = expression.argumentList.arguments.lastOrNull()?.unwrapArgument() as? FirAnonymousFunctionExpression ?: return
            val function = lambda.anonymousFunction
            val variable = function.valueParameters.firstOrNull()?.symbol ?: return
            val body = function.body ?: return
            report(subject, variable, body, config.literalLoop.maxElements)
        }
    }

    /** `for (x in listOf(...)) { }`, which FIR desugars to a block holding the iterator and a while loop. */
    object ForLoops : FirBlockChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirBlock) {
            val config = context.session.kotrailConfig
            if (!config.isEnabled(KotrailRule.NO_LITERAL_LOOP)) return
            if (expression.source?.kind != KtFakeSourceElementKind.DesugaredForLoop) return
            val iterator = (expression.statements.getOrNull(0) as? FirProperty)?.initializer as? FirFunctionCall ?: return
            val subject = iterator.explicitReceiver ?: return
            val whileLoop = expression.statements.getOrNull(1) as? FirWhileLoop ?: return
            val loopVariable = whileLoop.block.statements.firstOrNull() as? FirProperty ?: return
            report(subject, loopVariable.symbol, whileLoop.block, config.literalLoop.maxElements)
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun report(subject: FirExpression, variable: FirBasedSymbol<*>, body: FirElement, maxElements: Int) {
        val shape = shapeOf(subject, maxElements) ?: return
        if (!body.branchesOn(variable)) return
        val source = subject.source ?: return
        reportKotrail(source, KotrailDiagnostics.LITERAL_LOOP, shape.what, shape.advice)
    }

    private class Shape(val what: String, val advice: String)

    /** A literal collection of at most [maxElements] (booleans at any count), or a literal collection joined to data. */
    private fun shapeOf(subject: FirExpression, maxElements: Int): Shape? {
        val expression = subject.unwrapArgument()
        literalElements(expression)?.let { elements ->
            val allBooleans = elements.isNotEmpty() && elements.all { it.resolvedType.isBoolean }
            if (elements.size > maxElements && !allBooleans) return null
            val what = if (elements.size == 1) "one literal element" else "${elements.size} literal elements"
            return Shape("loops over $what", "write the call out once per element instead of folding the cases into a loop the reader has to unfold")
        }
        val call = expression as? FirFunctionCall ?: return null
        if (call.calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString() != PLUS) return null
        val receiver = call.explicitReceiver?.unwrapArgument() ?: return null
        val argument = call.argumentList.arguments.singleOrNull()?.unwrapArgument() ?: return null
        val (literal, data) = when {
            literalElements(receiver) != null && literalElements(argument) == null -> receiver to argument
            literalElements(argument) != null && literalElements(receiver) == null -> argument to receiver
            else -> return null
        }
        val count = literalElements(literal)!!.size
        if (count > maxElements) return null
        val dataText = data.source?.text?.toString()?.trim()?.take(40) ?: "the data"
        val what = if (count == 1) "a literal sentinel" else "$count literal sentinels"
        return Shape("joins $what to '$dataText' before looping", "write the sentinel's call out, then loop over the data alone")
    }

    /** The elements of `listOf(a, b)` when every one is a literal, a constant, an enum entry or an object; null otherwise. */
    private fun literalElements(expression: FirExpression): List<FirExpression>? {
        val call = expression as? FirFunctionCall ?: return null
        val callee = call.calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString() ?: return null
        if (callee !in LITERAL_COLLECTIONS) return null
        val elements = call.argumentList.arguments.flatMap { argument ->
            (argument.unwrapArgument() as? FirVarargArgumentsExpression)?.arguments?.map { it.unwrapArgument() } ?: listOf(argument.unwrapArgument())
        }
        return elements.takeIf { list -> list.all { it.isLiteralLike() } }
    }

    private fun FirExpression.isLiteralLike(): Boolean = when (this) {
        is FirLiteralExpression -> true
        is FirResolvedQualifier -> true
        is FirPropertyAccessExpression -> {
            val symbol = calleeReference.toResolvedCallableSymbol()
            symbol is FirEnumEntrySymbol || (symbol is FirPropertySymbol && symbol.isConst)
        }
        is FirFunctionCall -> {
            val name = calleeReference.toResolvedCallableSymbol()?.name?.asString()
            (name == "unaryMinus" || name == "not") && explicitReceiver?.unwrapArgument()?.isLiteralLike() == true
        }
        else -> false
    }

    /** Whether the body decides something by the loop variable: a condition, a comparison, an elvis, a safe call, a type check on it. */
    private fun FirElement.branchesOn(variable: FirBasedSymbol<*>): Boolean {
        var found = false
        fun FirElement.reads(): Boolean {
            var reads = false
            accept(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (reads) return
                    if (element is FirPropertyAccessExpression && element.calleeReference.toResolvedCallableSymbol() == variable) reads = true
                    else element.acceptChildren(this)
                }
            })
            return reads
        }
        accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (found) return
                found = when (element) {
                    is FirWhenExpression -> element.subjectVariable?.initializer?.reads() == true ||
                        element.branches.any { it.condition.reads() }
                    is FirEqualityOperatorCall -> element.argumentList.arguments.any { it.reads() }
                    is FirElvisExpression -> element.lhs.reads()
                    is FirSafeCallExpression -> element.receiver.reads()
                    is FirTypeOperatorCall -> element.argumentList.arguments.any { it.reads() }
                    else -> false
                }
                if (!found) element.acceptChildren(this)
            }
        })
        return found
    }
}

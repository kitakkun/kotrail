@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.test.isTestFunction
import com.kitakkun.kotrail.fir.test.isTestSourcePath
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.contracts.description.LogicOperationKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirLoopExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirWhenExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirLoop
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.text
import org.jetbrains.kotlin.types.ConstantValueKind

/**
 * Reports a compound boolean condition written inline in an `if`, a subject-less `when` branch,
 * a `while` or a `do`-`while`, when it either repeats a condition written elsewhere in the module
 * or is too compound to read in place:
 *
 * ```kotlin
 * if (!visible && !includeInvisible && children.isEmpty()) return   // reported when written twice
 * if (a && b && c && d) submit()                                    // reported: four operands inline
 * if (a && b || c) submit()                                         // reported: && and || unparenthesized
 * val canSubmit = a && b && c && d; if (canSubmit) submit()         // fine: the decision has a name
 * if (event.isKeyDown && event.key == Key.Q) quit()                 // fine: reads in place
 * ```
 *
 * An inline condition says what it computes but not what it decides; a name does. The same
 * compound condition written in two places is one decision with two definitions, and they drift.
 * Conditions are compared by their source text with whitespace and comments removed.
 *
 * Duplicates are found through a module-wide index kept in memory while the module is checked,
 * so the occurrence checked first stays quiet and every later one names it. An `&&` whose left
 * side is a null comparison (`x != null && x.isEmpty()`) is a smart-cast guard and does not count
 * toward the lone-condition limit. Arguments of `require`, `check` and the like, `when (subject)`
 * branches, test functions and test source sets are not looked at.
 */
object NamedConditionChecker {
    /** `if` (a subject-less `when` in FIR) and subject-less `when` branches. */
    object Whens : FirWhenExpressionChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirWhenExpression) {
            if (expression.subjectVariable != null) return
            for (branch in expression.branches) {
                val condition = branch.condition
                if (condition is FirElseIfTrueCondition) continue
                checkCondition(condition)
            }
        }
    }

    /** `while` and `do`-`while`; a `for` loop's desugared `while` has a fake source and is skipped. */
    object Loops : FirLoopExpressionChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirLoop) {
            if (expression !is FirWhileLoop && expression !is FirDoWhileLoop) return
            checkCondition(expression.condition)
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkCondition(condition: FirExpression) {
        val session = context.session
        val config = session.kotrailConfig
        if (!config.isEnabled(KotrailRule.NAMED_CONDITION)) return
        val source = condition.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (condition.unwrapNot() !is FirBooleanOperatorExpression) return
        val file = context.containingFileSymbol?.fir ?: return
        val path = file.sourceFile?.path?.replace('\\', '/') ?: file.name
        if (isTestSourcePath(path) || inTestFunction(session, config.test.annotations)) return
        val text = source.text ?: return

        val shape = Shape().apply { visit(condition) }
        val settings = config.namedCondition
        if (shape.operators >= settings.minOperators.coerceAtLeast(1)) {
            val line = file.sourceFileLinesMapping?.getLineByOffset(source.startOffset)?.plus(1)
            val here = Occurrence(path, source.startOffset, if (line != null && line > 0) "${file.name}:$line" else file.name)
            val other = session.namedConditionIndex.record(normalizeCondition(text), here)
            if (other != null) {
                reportKotrail(source, KotrailDiagnostics.CONDITION_DUPLICATED, collapse(text), other.where)
                return
            }
        }
        when {
            shape.mixed -> reportKotrail(source, KotrailDiagnostics.CONDITION_TOO_COMPOUND, collapse(text), "mixed && and ||")
            settings.maxOperators > 0 && shape.counted >= settings.maxOperators ->
                reportKotrail(source, KotrailDiagnostics.CONDITION_TOO_COMPOUND, collapse(text), shape.counted.toString())
        }
    }

    context(context: CheckerContext)
    private fun inTestFunction(session: FirSession, annotations: List<String>): Boolean =
        context.containingDeclarations.any { it is FirNamedFunctionSymbol && it.isTestFunction(session, annotations) }

    /** The `&&` and `||` operators of a condition, seen through `!`. */
    private class Shape {
        /** Every `&&` and `||`. */
        var operators = 0

        /** Those that count toward the lone-condition limit: all but an `&&` guarded by a null comparison. */
        var counted = 0

        /** Whether an `||` has an unparenthesized `&&` operand, i.e. `a && b || c`. */
        var mixed = false

        fun visit(expression: FirExpression) {
            val operator = expression.unwrapNot() as? FirBooleanOperatorExpression ?: return
            operators++
            if (!(operator.kind == LogicOperationKind.AND && operator.leftOperand.isNullComparison())) counted++
            if (operator.kind == LogicOperationKind.OR) {
                for (operand in listOf(operator.leftOperand, operator.rightOperand)) {
                    if (operand is FirBooleanOperatorExpression && operand.kind == LogicOperationKind.AND && !operand.isParenthesizedIn(operator)) {
                        mixed = true
                    }
                }
            }
            visit(operator.leftOperand)
            visit(operator.rightOperand)
        }
    }

    /** `!x` is `x.not()` in FIR; the operators under it still count. */
    private fun FirExpression.unwrapNot(): FirExpression {
        var current = this
        while (current is FirFunctionCall && current.calleeReference.name.asString() == "not" && current.argumentList.arguments.isEmpty()) {
            current = current.explicitReceiver ?: return current
        }
        return current
    }

    private fun FirExpression.isNullComparison(): Boolean {
        if (this !is FirEqualityOperatorCall) return false
        if (operation != FirOperation.EQ && operation != FirOperation.NOT_EQ && operation != FirOperation.IDENTITY && operation != FirOperation.NOT_IDENTITY) {
            return false
        }
        return argumentList.arguments.any { it is FirLiteralExpression && it.kind == ConstantValueKind.Null }
    }

    /** FIR drops parentheses, so they are read back from the text between the operator's start and the operand's. */
    private fun FirExpression.isParenthesizedIn(parent: FirExpression): Boolean {
        val operandSource = source ?: return true
        val parentSource = parent.source ?: return true
        val operandText = operandSource.text ?: return true
        if (operandText.startsWith("(")) return true
        val parentText = parentSource.text ?: return true
        val prefixLength = operandSource.startOffset - parentSource.startOffset
        if (prefixLength !in 0..parentText.length) return true
        return parentText.subSequence(0, prefixLength).trimEnd().endsWith("(")
    }

    private fun collapse(text: CharSequence): String = text.toString().trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
}

/**
 * The condition's text without whitespace and comments, string and character literals kept as
 * written; two conditions with the same key are the same condition, identifiers included.
 */
internal fun normalizeCondition(text: CharSequence): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val next = text.getOrNull(i + 1)
        when {
            c == '"' || c == '\'' -> {
                val end = literalEnd(text, i)
                out.append(text, i, end)
                i = end
            }
            c == '/' && next == '/' -> while (i < text.length && text[i] != '\n') i++
            c == '/' && next == '*' -> {
                val end = text.indexOf("*/", startIndex = i + 2)
                i = if (end < 0) text.length else end + 2
            }
            c.isWhitespace() -> i++
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

/** The index just past the string or character literal starting at [start]. */
private fun literalEnd(text: CharSequence, start: Int): Int {
    if (text.startsWith("\"\"\"", start)) {
        val end = text.indexOf("\"\"\"", startIndex = start + 3)
        return if (end < 0) text.length else end + 3
    }
    val quote = text[start]
    var i = start + 1
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i += 2
            quote -> return i + 1
            else -> i++
        }
    }
    return text.length
}

/** Where a compound condition is written: the file's path and offset identify it, [where] is what a message shows. */
class Occurrence(val path: String, val offset: Int, val where: String)

/**
 * The compound conditions seen so far in this module, by normalized text.
 *
 * Kept in memory for the length of one compilation, so it sees files in the order the compiler
 * checks them: the first occurrence of a condition finds nothing and every later one finds it.
 */
class NamedConditionIndex(session: FirSession) : FirExtensionSessionComponent(session) {
    private val occurrences = HashMap<String, MutableList<Occurrence>>()

    /** Records [occurrence] under [key] and returns the first other occurrence recorded before it, if any. */
    @Synchronized
    fun record(key: String, occurrence: Occurrence): Occurrence? {
        val list = occurrences.getOrPut(key) { mutableListOf() }
        val isSame = { it: Occurrence -> it.path == occurrence.path && it.offset == occurrence.offset }
        val other = list.firstOrNull { !isSame(it) }
        if (list.none(isSame)) list += occurrence
        return other
    }
}

val FirSession.namedConditionIndex: NamedConditionIndex by FirSession.sessionComponentAccessor()

package com.kitakkun.kotrail.preconditions

import com.kitakkun.kotrail.preconditions.Value.BoolV
import com.kitakkun.kotrail.preconditions.Value.CharV
import com.kitakkun.kotrail.preconditions.Value.DoubleV
import com.kitakkun.kotrail.preconditions.Value.FloatV
import com.kitakkun.kotrail.preconditions.Value.IntV
import com.kitakkun.kotrail.preconditions.Value.ListV
import com.kitakkun.kotrail.preconditions.Value.LongV
import com.kitakkun.kotrail.preconditions.Value.NullV
import com.kitakkun.kotrail.preconditions.Value.StrV

/**
 * The expression language preconditions are written in. It is deliberately small: comparisons,
 * equality, logic, arithmetic, ranges, and a handful of members of `String` and `List`. Every
 * node can be rendered to Kotlin-looking text and read back by [CondParser]; nodes that only
 * exist for constant folding (`if`, `?:`, conversions) render to `null` and are never exported.
 *
 * Evaluation is three-valued: [evaluate] returns `null` whenever any input is unknown, and
 * `&&` / `||` short-circuit on a known operand, so a condition is reported only when it is
 * false for every possible value of the unknown parts.
 */
sealed class Cond {
    data class Const(val value: Value) : Cond()
    data class Param(val name: String) : Cond()
    data class Not(val operand: Cond) : Cond()
    data class And(val left: Cond, val right: Cond) : Cond()
    data class Or(val left: Cond, val right: Cond) : Cond()
    data class Compare(val op: CompareOp, val left: Cond, val right: Cond) : Cond()
    data class Equals(val negated: Boolean, val left: Cond, val right: Cond) : Cond()
    data class Arith(val op: ArithOp, val left: Cond, val right: Cond) : Cond()
    data class Negate(val operand: Cond) : Cond()
    data class InRange(val negated: Boolean, val subject: Cond, val low: Cond, val high: Cond, val inclusive: Boolean) : Cond()
    data class Member(val receiver: Cond, val member: MemberKind) : Cond()

    /** Fold-only nodes: understood by the evaluator, never exported. */
    data class If(val condition: Cond, val thenBranch: Cond, val elseBranch: Cond) : Cond()
    data class Elvis(val left: Cond, val right: Cond) : Cond()
    data class Convert(val operand: Cond, val target: NumericKind) : Cond()

    enum class CompareOp(val symbol: String) { LT("<"), LE("<="), GT(">"), GE(">=") }
    enum class ArithOp(val symbol: String) { PLUS("+"), MINUS("-"), TIMES("*"), DIV("/"), REM("%") }
    enum class NumericKind { INT, LONG, FLOAT, DOUBLE }

    enum class MemberKind(val text: String, val isCall: Boolean) {
        LENGTH("length", isCall = false),
        SIZE("size", isCall = false),
        IS_EMPTY("isEmpty", isCall = true),
        IS_NOT_EMPTY("isNotEmpty", isCall = true),
        IS_BLANK("isBlank", isCall = true),
        IS_NOT_BLANK("isNotBlank", isCall = true);

        companion object {
            fun byText(text: String): MemberKind? = entries.firstOrNull { it.text == text }
        }
    }

    /** Every parameter this condition depends on. */
    fun parameters(): Set<String> {
        val result = LinkedHashSet<String>()
        fun walk(cond: Cond) {
            when (cond) {
                is Const -> Unit
                is Param -> result += cond.name
                is Not -> walk(cond.operand)
                is And -> { walk(cond.left); walk(cond.right) }
                is Or -> { walk(cond.left); walk(cond.right) }
                is Compare -> { walk(cond.left); walk(cond.right) }
                is Equals -> { walk(cond.left); walk(cond.right) }
                is Arith -> { walk(cond.left); walk(cond.right) }
                is Negate -> walk(cond.operand)
                is InRange -> { walk(cond.subject); walk(cond.low); walk(cond.high) }
                is Member -> walk(cond.receiver)
                is If -> { walk(cond.condition); walk(cond.thenBranch); walk(cond.elseBranch) }
                is Elvis -> { walk(cond.left); walk(cond.right) }
                is Convert -> walk(cond.operand)
            }
        }
        walk(this)
        return result
    }

    /** The value under [env], or `null` when it cannot be determined. */
    fun evaluate(env: Map<String, Value>): Value? = when (this) {
        is Const -> value
        is Param -> env[name]
        is Not -> (operand.evaluate(env) as? BoolV)?.let { BoolV(!it.value) }
        is And -> {
            val l = left.evaluate(env) as? BoolV
            val r = right.evaluate(env) as? BoolV
            when {
                l != null && !l.value -> BoolV(false)
                r != null && !r.value -> BoolV(false)
                l != null && r != null -> BoolV(true)
                else -> null
            }
        }
        is Or -> {
            val l = left.evaluate(env) as? BoolV
            val r = right.evaluate(env) as? BoolV
            when {
                l != null && l.value -> BoolV(true)
                r != null && r.value -> BoolV(true)
                l != null && r != null -> BoolV(false)
                else -> null
            }
        }
        is Compare -> {
            val l = left.evaluate(env)
            val r = right.evaluate(env)
            if (l == null || r == null) null else compareValues(l, r)?.let { c ->
                BoolV(
                    when (op) {
                        CompareOp.LT -> c < 0
                        CompareOp.LE -> c <= 0
                        CompareOp.GT -> c > 0
                        CompareOp.GE -> c >= 0
                    },
                )
            }
        }
        is Equals -> {
            val l = left.evaluate(env)
            val r = right.evaluate(env)
            if (l == null || r == null) null else valuesEqual(l, r)?.let { BoolV(it != negated) }
        }
        is Arith -> {
            val l = left.evaluate(env)
            val r = right.evaluate(env)
            if (l == null || r == null) null else arithmetic(op, l, r)
        }
        is Negate -> when (val v = operand.evaluate(env)) {
            is IntV -> IntV(-v.value)
            is LongV -> LongV(-v.value)
            is FloatV -> FloatV(-v.value)
            is DoubleV -> DoubleV(-v.value)
            else -> null
        }
        is InRange -> {
            val s = subject.evaluate(env)
            val lo = low.evaluate(env)
            val hi = high.evaluate(env)
            if (s == null || lo == null || hi == null) {
                null
            } else {
                val aboveLow = compareValues(s, lo)?.let { it >= 0 }
                val belowHigh = compareValues(s, hi)?.let { if (inclusive) it <= 0 else it < 0 }
                if (aboveLow == null || belowHigh == null) null else BoolV((aboveLow && belowHigh) != negated)
            }
        }
        is Member -> when (val v = receiver.evaluate(env)) {
            is StrV -> when (member) {
                MemberKind.LENGTH -> IntV(v.value.length)
                MemberKind.IS_EMPTY -> BoolV(v.value.isEmpty())
                MemberKind.IS_NOT_EMPTY -> BoolV(v.value.isNotEmpty())
                MemberKind.IS_BLANK -> BoolV(v.value.isBlank())
                MemberKind.IS_NOT_BLANK -> BoolV(v.value.isNotBlank())
                MemberKind.SIZE -> null
            }
            is ListV -> when (member) {
                MemberKind.SIZE -> IntV(v.elements.size)
                MemberKind.IS_EMPTY -> BoolV(v.elements.isEmpty())
                MemberKind.IS_NOT_EMPTY -> BoolV(v.elements.isNotEmpty())
                else -> null
            }
            else -> null
        }
        is If -> when (val c = condition.evaluate(env) as? BoolV) {
            null -> null
            else -> if (c.value) thenBranch.evaluate(env) else elseBranch.evaluate(env)
        }
        is Elvis -> when (val l = left.evaluate(env)) {
            null -> null
            NullV -> right.evaluate(env)
            else -> l
        }
        is Convert -> {
            val v = operand.evaluate(env)
            if (v == null || !(v.isNumeric || v is CharV)) {
                null
            } else {
                when (target) {
                    NumericKind.INT -> if (v.isFloating) IntV(v.toDouble().toInt()) else IntV(v.toLong().toInt())
                    NumericKind.LONG -> if (v.isFloating) LongV(v.toDouble().toLong()) else LongV(v.toLong())
                    NumericKind.FLOAT -> FloatV(v.toDouble().toFloat())
                    NumericKind.DOUBLE -> DoubleV(v.toDouble())
                }
            }
        }
    }

    /** Kotlin-looking text that [CondParser] reads back, or `null` when the tree holds fold-only nodes. */
    fun render(): String? = try {
        renderWithPrecedence()
    } catch (_: UnrenderableException) {
        null
    }

    private class UnrenderableException : RuntimeException()

    private fun renderWithPrecedence(): String = when (this) {
        is Const -> value.render()
        is Param -> name
        is Not -> "!" + operand.renderChild(PREC_UNARY)
        is And -> left.renderChild(PREC_AND) + " && " + right.renderChild(PREC_AND + 1)
        is Or -> left.renderChild(PREC_OR) + " || " + right.renderChild(PREC_OR + 1)
        is Compare -> left.renderChild(PREC_COMPARE + 1) + " ${op.symbol} " + right.renderChild(PREC_COMPARE + 1)
        is Equals -> left.renderChild(PREC_EQUALS + 1) + (if (negated) " != " else " == ") + right.renderChild(PREC_EQUALS + 1)
        is Arith -> {
            val prec = precedence()
            left.renderChild(prec) + " ${op.symbol} " + right.renderChild(prec + 1)
        }
        is Negate -> "-" + operand.renderChild(PREC_UNARY)
        is InRange -> subject.renderChild(PREC_RANGE_CHECK + 1) + (if (negated) " !in " else " in ") +
            low.renderChild(PREC_RANGE + 1) + (if (inclusive) ".." else "..<") + high.renderChild(PREC_RANGE + 1)
        is Member -> receiver.renderChild(PREC_POSTFIX) + "." + member.text + (if (member.isCall) "()" else "")
        is If, is Elvis, is Convert -> throw UnrenderableException()
    }

    private fun renderChild(minPrecedence: Int): String {
        val text = renderWithPrecedence()
        return if (precedence() < minPrecedence) "($text)" else text
    }

    private fun precedence(): Int = when (this) {
        is Const, is Param, is Member -> PREC_POSTFIX
        is Not, is Negate -> PREC_UNARY
        is Arith -> if (op == ArithOp.PLUS || op == ArithOp.MINUS) PREC_ADDITIVE else PREC_MULTIPLICATIVE
        is InRange -> PREC_RANGE_CHECK
        is Compare -> PREC_COMPARE
        is Equals -> PREC_EQUALS
        is And -> PREC_AND
        is Or -> PREC_OR
        is If, is Elvis, is Convert -> throw UnrenderableException()
    }

    companion object {
        // Kotlin's operator precedence, high to low.
        const val PREC_POSTFIX = 10
        const val PREC_UNARY = 9
        const val PREC_MULTIPLICATIVE = 8
        const val PREC_ADDITIVE = 7
        const val PREC_RANGE = 6
        const val PREC_RANGE_CHECK = 5
        const val PREC_COMPARE = 4
        const val PREC_EQUALS = 3
        const val PREC_AND = 2
        const val PREC_OR = 1

        private fun compareValues(l: Value, r: Value): Int? = when {
            (l.isNumeric || l is CharV) && (r.isNumeric || r is CharV) ->
                if (l.isFloating || r.isFloating) l.toDouble().compareTo(r.toDouble()) else l.toLong().compareTo(r.toLong())
            l is StrV && r is StrV -> l.value.compareTo(r.value)
            else -> null
        }

        private fun valuesEqual(l: Value, r: Value): Boolean? = when {
            l is NullV || r is NullV -> l == r
            l.isNumeric && r.isNumeric ->
                if (l.isFloating || r.isFloating) l.toDouble() == r.toDouble() else l.toLong() == r.toLong()
            l is StrV && r is StrV -> l.value == r.value
            l is BoolV && r is BoolV -> l.value == r.value
            l is CharV && r is CharV -> l.value == r.value
            l is ListV && r is ListV -> l == r
            else -> null
        }

        private fun arithmetic(op: ArithOp, l: Value, r: Value): Value? {
            if (op == ArithOp.PLUS && l is StrV) return StrV(l.value + r.toDisplayString())
            if (!l.isNumeric || !r.isNumeric) return null
            if (!l.isFloating && !r.isFloating && (op == ArithOp.DIV || op == ArithOp.REM) && r.toLong() == 0L) return null
            return when {
                l is DoubleV || r is DoubleV -> DoubleV(applyDouble(op, l.toDouble(), r.toDouble()))
                l is FloatV || r is FloatV -> FloatV(applyFloat(op, l.toDouble().toFloat(), r.toDouble().toFloat()))
                l is LongV || r is LongV -> LongV(applyLong(op, l.toLong(), r.toLong()))
                else -> IntV(applyInt(op, (l as IntV).value, (r as IntV).value))
            }
        }

        private fun applyInt(op: ArithOp, a: Int, b: Int): Int = when (op) {
            ArithOp.PLUS -> a + b
            ArithOp.MINUS -> a - b
            ArithOp.TIMES -> a * b
            ArithOp.DIV -> a / b
            ArithOp.REM -> a % b
        }

        private fun applyLong(op: ArithOp, a: Long, b: Long): Long = when (op) {
            ArithOp.PLUS -> a + b
            ArithOp.MINUS -> a - b
            ArithOp.TIMES -> a * b
            ArithOp.DIV -> a / b
            ArithOp.REM -> a % b
        }

        private fun applyFloat(op: ArithOp, a: Float, b: Float): Float = when (op) {
            ArithOp.PLUS -> a + b
            ArithOp.MINUS -> a - b
            ArithOp.TIMES -> a * b
            ArithOp.DIV -> a / b
            ArithOp.REM -> a % b
        }

        private fun applyDouble(op: ArithOp, a: Double, b: Double): Double = when (op) {
            ArithOp.PLUS -> a + b
            ArithOp.MINUS -> a - b
            ArithOp.TIMES -> a * b
            ArithOp.DIV -> a / b
            ArithOp.REM -> a % b
        }
    }
}

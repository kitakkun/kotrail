package com.kitakkun.kotrail.preconditions

/**
 * A compile-time constant as the precondition evaluator sees it. Numeric kinds are kept apart so
 * that arithmetic follows Kotlin's own promotion and overflow rules.
 */
sealed class Value {
    data class IntV(val value: Int) : Value()
    data class LongV(val value: Long) : Value()
    data class FloatV(val value: Float) : Value()
    data class DoubleV(val value: Double) : Value()
    data class BoolV(val value: Boolean) : Value()
    data class StrV(val value: String) : Value()
    data class CharV(val value: Char) : Value()
    data class ListV(val elements: List<Value>) : Value()
    data object NullV : Value()

    val isNumeric: Boolean get() = this is IntV || this is LongV || this is FloatV || this is DoubleV
    val isIntegral: Boolean get() = this is IntV || this is LongV
    val isFloating: Boolean get() = this is FloatV || this is DoubleV

    fun toDouble(): Double = when (this) {
        is IntV -> value.toDouble()
        is LongV -> value.toDouble()
        is FloatV -> value.toDouble()
        is DoubleV -> value
        is CharV -> value.code.toDouble()
        else -> error("not numeric: $this")
    }

    fun toLong(): Long = when (this) {
        is IntV -> value.toLong()
        is LongV -> value
        is CharV -> value.code.toLong()
        else -> error("not integral: $this")
    }

    /** The text Kotlin's string templates would produce. */
    fun toDisplayString(): String = when (this) {
        is IntV -> value.toString()
        is LongV -> value.toString()
        is FloatV -> value.toString()
        is DoubleV -> value.toString()
        is BoolV -> value.toString()
        is StrV -> value
        is CharV -> value.toString()
        is ListV -> elements.joinToString(", ", "[", "]") { it.toDisplayString() }
        NullV -> "null"
    }

    /** A Kotlin literal that reads back to this value through [CondParser]. */
    fun render(): String = when (this) {
        is IntV -> value.toString()
        is LongV -> "${value}L"
        is FloatV -> renderFloating(value.toString(), "f")
        is DoubleV -> renderFloating(value.toString(), "")
        is BoolV -> value.toString()
        is StrV -> buildString {
            append('"')
            for (c in value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '$' -> append("\\$")
                    else -> append(c)
                }
            }
            append('"')
        }
        is CharV -> when (value) {
            '\\' -> "'\\\\'"
            '\'' -> "'\\''"
            '\n' -> "'\\n'"
            '\r' -> "'\\r'"
            '\t' -> "'\\t'"
            else -> "'$value'"
        }
        is ListV -> elements.joinToString(", ", "listOf(", ")") { it.render() }
        NullV -> "null"
    }

    private fun renderFloating(text: String, suffix: String): String {
        // NaN and infinities have no literal form; the parser understands these spellings.
        if (text == "NaN" || text == "Infinity" || text == "-Infinity") return text + suffix
        return if ('.' in text || 'E' in text || 'e' in text) text + suffix else "$text.0$suffix"
    }
}

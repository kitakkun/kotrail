package com.kitakkun.kotrail.preconditions

import com.kitakkun.kotrail.preconditions.Cond.ArithOp
import com.kitakkun.kotrail.preconditions.Cond.CompareOp
import com.kitakkun.kotrail.preconditions.Cond.MemberKind
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
 * Reads the text produced by [Cond.render] back into a tree. The grammar is the subset of Kotlin
 * expression syntax that [Cond] can express, with Kotlin's precedence:
 *
 * ```
 * or        := and ('||' and)*
 * and       := equality ('&&' equality)*
 * equality  := compare (('==' | '!=') compare)*
 * compare   := range (('<' | '<=' | '>' | '>=') range)*
 * range     := additive (('in' | '!in') additive ('..' | '..<') additive)?
 * additive  := multiplicative (('+' | '-') multiplicative)*
 * multiplicative := unary (('*' | '/' | '%') unary)*
 * unary     := ('!' | '-') unary | postfix
 * postfix   := primary ('.' member '()'?)*
 * primary   := literal | identifier | 'listOf' '(' (or (',' or)*)? ')' | '(' or ')'
 * ```
 *
 * [parse] returns `null` for anything it does not understand, so a metadata string written by a
 * newer or older plugin version is ignored rather than misread.
 */
object CondParser {
    fun parse(text: String): Cond? = try {
        val parser = Parser(Lexer(text).tokens())
        val result = parser.parseOr()
        if (parser.atEnd()) result else null
    } catch (_: ParseException) {
        null
    }

    private class ParseException : RuntimeException()

    private sealed class Token {
        data class Num(val value: Value) : Token()
        data class Str(val value: String) : Token()
        data class Chr(val value: Char) : Token()
        data class Ident(val name: String) : Token()
        data class Op(val text: String) : Token()
    }

    private class Lexer(private val text: String) {
        private var index = 0

        fun tokens(): List<Token> {
            val result = mutableListOf<Token>()
            while (true) {
                skipWhitespace()
                if (index >= text.length) return result
                result += next()
            }
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun next(): Token {
            val c = text[index]
            return when {
                c.isDigit() -> number()
                c == '"' -> string()
                c == '\'' -> char()
                c.isLetter() || c == '_' -> identifier()
                else -> operator()
            }
        }

        private fun number(): Token {
            val start = index
            while (index < text.length && (text[index].isDigit() || text[index] == '_')) index++
            var floating = false
            if (index + 1 < text.length && text[index] == '.' && text[index + 1].isDigit()) {
                floating = true
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                floating = true
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val digits = text.substring(start, index).replace("_", "")
            if (index < text.length) {
                when (text[index]) {
                    'L' -> { index++; return Token.Num(LongV(digits.toLongOrNull() ?: throw ParseException())) }
                    'f', 'F' -> { index++; return Token.Num(FloatV(digits.toFloatOrNull() ?: throw ParseException())) }
                }
            }
            return if (floating) {
                Token.Num(DoubleV(digits.toDoubleOrNull() ?: throw ParseException()))
            } else {
                Token.Num(IntV(digits.toIntOrNull() ?: throw ParseException()))
            }
        }

        private fun string(): Token {
            index++ // opening quote
            val builder = StringBuilder()
            while (true) {
                if (index >= text.length) throw ParseException()
                val c = text[index++]
                when (c) {
                    '"' -> return Token.Str(builder.toString())
                    '\\' -> builder.append(escape())
                    else -> builder.append(c)
                }
            }
        }

        private fun char(): Token {
            index++ // opening quote
            if (index >= text.length) throw ParseException()
            val c = text[index++]
            val value = if (c == '\\') escape() else c
            if (index >= text.length || text[index] != '\'') throw ParseException()
            index++
            return Token.Chr(value)
        }

        private fun escape(): Char {
            if (index >= text.length) throw ParseException()
            return when (text[index++]) {
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'b' -> '\b'
                '\\' -> '\\'
                '"' -> '"'
                '\'' -> '\''
                '$' -> '$'
                else -> throw ParseException()
            }
        }

        private fun identifier(): Token {
            val start = index
            while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) index++
            return Token.Ident(text.substring(start, index))
        }

        private fun operator(): Token {
            for (candidate in OPERATORS) {
                if (text.startsWith(candidate, index)) {
                    index += candidate.length
                    return Token.Op(candidate)
                }
            }
            throw ParseException()
        }

        companion object {
            // Longest first so that `..<` wins over `..`, and `<=` over `<`.
            private val OPERATORS = listOf("..<", "..", "||", "&&", "==", "!=", "<=", ">=", "<", ">", "!", "+", "-", "*", "/", "%", "(", ")", ".", ",")
        }
    }

    private class Parser(private val tokens: List<Token>) {
        private var index = 0

        fun atEnd(): Boolean = index >= tokens.size

        private fun peek(): Token? = tokens.getOrNull(index)
        private fun peekOp(text: String): Boolean = (peek() as? Token.Op)?.text == text
        private fun peekIdent(name: String): Boolean = (peek() as? Token.Ident)?.name == name

        private fun expectOp(text: String) {
            if (!peekOp(text)) throw ParseException()
            index++
        }

        fun parseOr(): Cond {
            var left = parseAnd()
            while (peekOp("||")) {
                index++
                left = Cond.Or(left, parseAnd())
            }
            return left
        }

        private fun parseAnd(): Cond {
            var left = parseEquality()
            while (peekOp("&&")) {
                index++
                left = Cond.And(left, parseEquality())
            }
            return left
        }

        private fun parseEquality(): Cond {
            var left = parseCompare()
            while (peekOp("==") || peekOp("!=")) {
                val negated = (tokens[index++] as Token.Op).text == "!="
                left = Cond.Equals(negated, left, parseCompare())
            }
            return left
        }

        private fun parseCompare(): Cond {
            var left = parseRange()
            while (true) {
                val op = when {
                    peekOp("<=") -> CompareOp.LE
                    peekOp(">=") -> CompareOp.GE
                    peekOp("<") -> CompareOp.LT
                    peekOp(">") -> CompareOp.GT
                    else -> return left
                }
                index++
                left = Cond.Compare(op, left, parseRange())
            }
        }

        private fun parseRange(): Cond {
            val subject = parseAdditive()
            val negated = when {
                peekIdent("in") -> false
                peekOp("!") && (tokens.getOrNull(index + 1) as? Token.Ident)?.name == "in" -> true
                else -> return subject
            }
            index += if (negated) 2 else 1
            val low = parseAdditive()
            val inclusive = when {
                peekOp("..<") -> false
                peekOp("..") -> true
                else -> throw ParseException()
            }
            index++
            val high = parseAdditive()
            return Cond.InRange(negated, subject, low, high, inclusive)
        }

        private fun parseAdditive(): Cond {
            var left = parseMultiplicative()
            while (true) {
                val op = when {
                    peekOp("+") -> ArithOp.PLUS
                    peekOp("-") -> ArithOp.MINUS
                    else -> return left
                }
                index++
                left = Cond.Arith(op, left, parseMultiplicative())
            }
        }

        private fun parseMultiplicative(): Cond {
            var left = parseUnary()
            while (true) {
                val op = when {
                    peekOp("*") -> ArithOp.TIMES
                    peekOp("/") -> ArithOp.DIV
                    peekOp("%") -> ArithOp.REM
                    else -> return left
                }
                index++
                left = Cond.Arith(op, left, parseUnary())
            }
        }

        private fun parseUnary(): Cond = when {
            peekOp("!") -> { index++; Cond.Not(parseUnary()) }
            peekOp("-") -> { index++; Cond.Negate(parseUnary()) }
            else -> parsePostfix()
        }

        private fun parsePostfix(): Cond {
            var result = parsePrimary()
            while (peekOp(".")) {
                index++
                val name = (tokens.getOrNull(index++) as? Token.Ident)?.name ?: throw ParseException()
                val member = MemberKind.byText(name) ?: throw ParseException()
                if (member.isCall) {
                    expectOp("(")
                    expectOp(")")
                }
                result = Cond.Member(result, member)
            }
            return result
        }

        private fun parsePrimary(): Cond {
            return when (val token = tokens.getOrNull(index++) ?: throw ParseException()) {
                is Token.Num -> Cond.Const(token.value)
                is Token.Str -> Cond.Const(StrV(token.value))
                is Token.Chr -> Cond.Const(CharV(token.value))
                is Token.Ident -> when (token.name) {
                    "true" -> Cond.Const(BoolV(true))
                    "false" -> Cond.Const(BoolV(false))
                    "null" -> Cond.Const(NullV)
                    "NaN" -> Cond.Const(DoubleV(Double.NaN))
                    "NaNf" -> Cond.Const(FloatV(Float.NaN))
                    "Infinity" -> Cond.Const(DoubleV(Double.POSITIVE_INFINITY))
                    "Infinityf" -> Cond.Const(FloatV(Float.POSITIVE_INFINITY))
                    "listOf" -> parseListOf()
                    "in" -> throw ParseException()
                    else -> Cond.Param(token.name)
                }
                is Token.Op -> {
                    if (token.text != "(") throw ParseException()
                    val inner = parseOr()
                    expectOp(")")
                    inner
                }
            }
        }

        private fun parseListOf(): Cond {
            expectOp("(")
            val elements = mutableListOf<Value>()
            if (!peekOp(")")) {
                while (true) {
                    val element = parseOr().evaluate(emptyMap()) ?: throw ParseException()
                    elements += element
                    if (peekOp(",")) { index++; continue }
                    break
                }
            }
            expectOp(")")
            return Cond.Const(ListV(elements))
        }
    }
}

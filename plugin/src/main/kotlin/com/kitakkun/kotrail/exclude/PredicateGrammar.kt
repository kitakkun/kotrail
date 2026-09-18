package com.kitakkun.kotrail.exclude

/**
 * The grammar every Kotrail predicate shares:
 *
 * ```
 * or    := and ('||' and)*
 * and   := unary ('&&' unary)*
 * unary := '!' unary | '(' or ')' | atom
 * atom  := IDENT ('(' ARGUMENT ')')?
 * ```
 *
 * `ARGUMENT` is everything up to the closing parenthesis, trimmed, so globs and fully qualified
 * names need no quoting. What the atoms mean is the caller's: [parse] takes the atom factory and
 * the three combinators, and reports a malformed predicate as an [ExcludeParser.ExcludeSyntaxException]
 * with the offending position, which the configuration loader turns into a build failure.
 */
internal object PredicateGrammar {
    class Atoms<T>(
        val atom: (name: String, argument: String?, fail: (String) -> Nothing) -> T,
        val not: (T) -> T,
        val and: (T, T) -> T,
        val or: (T, T) -> T,
    )

    fun <T> parse(text: String, atoms: Atoms<T>): T {
        val parser = Parser(text, atoms)
        val result = parser.parseOr()
        parser.expectEnd()
        return result
    }

    private class Parser<T>(private val text: String, private val atoms: Atoms<T>) {
        private var index = 0

        fun parseOr(): T {
            var left = parseAnd()
            while (consume("||")) left = atoms.or(left, parseAnd())
            return left
        }

        private fun parseAnd(): T {
            var left = parseUnary()
            while (consume("&&")) left = atoms.and(left, parseUnary())
            return left
        }

        private fun parseUnary(): T {
            skipSpaces()
            return when {
                consume("!") -> atoms.not(parseUnary())
                consume("(") -> parseOr().also { expect(")") }
                else -> parseAtom()
            }
        }

        private fun parseAtom(): T {
            skipSpaces()
            val start = index
            while (index < text.length && text[index].isLetter()) index++
            val name = text.substring(start, index)
            if (name.isEmpty()) fail("expected a predicate such as package(...) or extension")
            skipSpaces()
            val argument = if (consume("(")) {
                val close = text.indexOf(')', index)
                if (close < 0) fail("missing ')' after '$name('")
                text.substring(index, close).trim().also { index = close + 1 }
            } else {
                null
            }
            return atoms.atom(name, argument) { message -> fail(message, start) }
        }

        fun expectEnd() {
            skipSpaces()
            if (index < text.length) fail("unexpected '${text.substring(index).take(20)}'")
        }

        private fun expect(token: String) {
            if (!consume(token)) fail("expected '$token'")
        }

        private fun consume(token: String): Boolean {
            skipSpaces()
            if (!text.startsWith(token, index)) return false
            index += token.length
            return true
        }

        private fun skipSpaces() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun fail(message: String, position: Int = index): Nothing =
            throw ExcludeParser.ExcludeSyntaxException("$message (at position ${position + 1} of '$text')")
    }
}

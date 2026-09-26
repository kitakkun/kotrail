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
 *
 * [parse] also takes the project's named predicates (the top-level `predicates` mapping): a bare
 * `IDENT` that names one stands for that predicate's text, parsed in place with the same atoms,
 * and wrapped by [Atoms.alias] so that a rendering shows both the name and what it expands to.
 * A named predicate takes no argument, and one that expands to itself, directly or through
 * others, is reported with the cycle.
 */
internal object PredicateGrammar {
    class Atoms<T>(
        val atom: (name: String, argument: String?, fail: (String) -> Nothing) -> T,
        val not: (T) -> T,
        val and: (T, T) -> T,
        val or: (T, T) -> T,
        val alias: (name: String, expansion: T) -> T,
    )

    fun <T> parse(text: String, atoms: Atoms<T>, aliases: Map<String, String> = emptyMap()): T =
        parse(text, atoms, aliases, emptyList())

    private fun <T> parse(text: String, atoms: Atoms<T>, aliases: Map<String, String>, expanding: List<String>): T {
        val parser = Parser(text, atoms, aliases, expanding)
        val result = parser.parseOr()
        parser.expectEnd()
        return result
    }

    private class Parser<T>(
        private val text: String,
        private val atoms: Atoms<T>,
        private val aliases: Map<String, String>,
        /** The named predicates being expanded, outermost first; a name already here is a cycle. */
        private val expanding: List<String>,
    ) {
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
            val alias = aliases[name]
            if (alias != null) {
                if (argument != null) fail("'$name' is a named predicate and takes no argument", start)
                if (name in expanding) {
                    val cycle = (expanding.dropWhile { it != name } + name).joinToString(" -> ")
                    fail("named predicates form a cycle: $cycle", start)
                }
                val expansion = try {
                    parse(alias, atoms, aliases, expanding + name)
                } catch (e: ExcludeParser.ExcludeSyntaxException) {
                    if (expanding.isNotEmpty()) throw e
                    fail("in the named predicate '$name': ${e.message}", start)
                }
                return atoms.alias(name, expansion)
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

package com.kitakkun.kotrail.exclude

/**
 * Parses an exclusion predicate:
 *
 * ```
 * or    := and ('||' and)*
 * and   := unary ('&&' unary)*
 * unary := '!' unary | '(' or ')' | atom
 * atom  := IDENT ('(' ARGUMENT ')')?
 * ```
 *
 * `class` is the one atom with and without an argument: `class(glob)` asks about enclosing
 * class names, bare `class` whether the declaration itself is a class, like `function` and
 * `property`.
 *
 * `ARGUMENT` is everything up to the closing parenthesis, trimmed, so globs and fully qualified
 * names need no quoting. A malformed predicate is reported as a [ExcludeSyntaxException] with the
 * offending position, and the configuration loader turns that into a build failure.
 */
object ExcludeParser {
    fun parse(text: String): ExcludePredicate {
        val parser = Parser(text)
        val result = parser.parseOr()
        parser.expectEnd()
        return result
    }

    class ExcludeSyntaxException(message: String) : RuntimeException(message)

    private val VISIBILITIES = setOf("public", "internal", "protected", "private")

    private class Parser(private val text: String) {
        private var index = 0

        fun parseOr(): ExcludePredicate {
            var left = parseAnd()
            while (consume("||")) left = ExcludePredicate.Or(left, parseAnd())
            return left
        }

        private fun parseAnd(): ExcludePredicate {
            var left = parseUnary()
            while (consume("&&")) left = ExcludePredicate.And(left, parseUnary())
            return left
        }

        private fun parseUnary(): ExcludePredicate {
            skipSpaces()
            return when {
                consume("!") -> ExcludePredicate.Not(parseUnary())
                consume("(") -> parseOr().also { expect(")") }
                else -> parseAtom()
            }
        }

        private fun parseAtom(): ExcludePredicate {
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
            return atom(name, argument, start)
        }

        private fun atom(name: String, argument: String?, position: Int): ExcludePredicate {
            fun required(): String = argument?.takeIf { it.isNotEmpty() }
                ?: fail("'$name' needs an argument, as in $name(...)", position)
            fun none() {
                if (argument != null) fail("'$name' takes no argument", position)
            }
            return when (name) {
                "package" -> ExcludePredicate.PackageIs(Glob(required()))
                "file" -> ExcludePredicate.FileIs(Glob(required()))
                "name" -> ExcludePredicate.NameIs(Glob(required()))
                "class" -> if (argument == null) ExcludePredicate.KindIs(DeclarationKind.CLASS) else ExcludePredicate.ClassIs(Glob(required()))
                "function" -> { none(); ExcludePredicate.KindIs(DeclarationKind.FUNCTION) }
                "property" -> { none(); ExcludePredicate.KindIs(DeclarationKind.PROPERTY) }
                "annotated" -> ExcludePredicate.Annotated(required())
                "extension" -> ExcludePredicate.Extension(argument?.takeIf { it.isNotEmpty() })
                "context" -> ExcludePredicate.Context(argument?.takeIf { it.isNotEmpty() })
                "visibility" -> {
                    val visibility = required()
                    if (visibility !in VISIBILITIES) fail("visibility must be one of ${VISIBILITIES.joinToString()}, got '$visibility'", position)
                    ExcludePredicate.VisibilityIs(visibility)
                }
                "override" -> { none(); ExcludePredicate.Override }
                "suspend" -> { none(); ExcludePredicate.Suspend }
                "inline" -> { none(); ExcludePredicate.Inline }
                "composable" -> { none(); ExcludePredicate.Composable }
                "test" -> { none(); ExcludePredicate.Test }
                else -> fail("unknown predicate '$name'", position)
            }
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
            throw ExcludeSyntaxException("$message (at position ${position + 1} of '$text')")
    }
}

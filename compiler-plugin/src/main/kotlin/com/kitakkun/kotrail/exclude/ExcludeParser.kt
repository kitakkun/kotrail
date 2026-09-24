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
 * The grammar is [PredicateGrammar]'s; this object supplies the atoms that ask about a
 * declaration's location. A malformed predicate is reported as an [ExcludeSyntaxException] with
 * the offending position, and the configuration loader turns that into a build failure.
 */
object ExcludeParser {
    fun parse(text: String): ExcludePredicate = PredicateGrammar.parse(text, ATOMS)

    class ExcludeSyntaxException(message: String) : RuntimeException(message)

    private val VISIBILITIES = setOf("public", "internal", "protected", "private")

    private val ATOMS = PredicateGrammar.Atoms(
        atom = ::atom,
        not = ExcludePredicate::Not,
        and = ExcludePredicate::And,
        or = ExcludePredicate::Or,
    )

    private fun atom(name: String, argument: String?, fail: (String) -> Nothing): ExcludePredicate {
        fun required(): String = argument?.takeIf { it.isNotEmpty() }
            ?: fail("'$name' needs an argument, as in $name(...)")
        fun none() {
            if (argument != null) fail("'$name' takes no argument")
        }
        return when (name) {
            "package" -> ExcludePredicate.PackageIs(Glob(required()))
            "file" -> ExcludePredicate.FileIs(Glob(required()))
            "path" -> ExcludePredicate.PathIs(Glob(required()))
            "name" -> ExcludePredicate.NameIs(Glob(required()))
            "class" -> if (argument == null) ExcludePredicate.KindIs(DeclarationKind.CLASS) else ExcludePredicate.ClassIs(Glob(required()))
            "function" -> { none(); ExcludePredicate.KindIs(DeclarationKind.FUNCTION) }
            "property" -> { none(); ExcludePredicate.KindIs(DeclarationKind.PROPERTY) }
            "annotated" -> ExcludePredicate.Annotated(required())
            "extension" -> ExcludePredicate.Extension(argument?.takeIf { it.isNotEmpty() })
            "context" -> ExcludePredicate.Context(argument?.takeIf { it.isNotEmpty() })
            "visibility" -> {
                val visibility = required()
                if (visibility !in VISIBILITIES) fail("visibility must be one of ${VISIBILITIES.joinToString()}, got '$visibility'")
                ExcludePredicate.VisibilityIs(visibility)
            }
            "override" -> { none(); ExcludePredicate.Override }
            "suspend" -> { none(); ExcludePredicate.Suspend }
            "inline" -> { none(); ExcludePredicate.Inline }
            "composable" -> { none(); ExcludePredicate.Composable }
            "test" -> { none(); ExcludePredicate.Test }
            else -> fail("unknown predicate '$name'")
        }
    }
}

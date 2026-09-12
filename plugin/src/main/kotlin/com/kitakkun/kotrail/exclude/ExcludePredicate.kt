package com.kitakkun.kotrail.exclude

/**
 * A predicate over a [ReportSite], written in the configuration as
 * `exclude.<rule>=extension(kotlin.String) || annotated(com.acme.Generated)`.
 *
 * Atoms ask about the location a diagnostic is reported at, never about what was found:
 *
 * | Atom | True when |
 * |---|---|
 * | `package(glob)`, `file(glob)` | the file's package / file name matches |
 * | `name(glob)` | the innermost declaration's name matches |
 * | `class(glob)` | any enclosing class (or the declaration itself, if a class) matches |
 * | `annotated(fqn)` | the innermost or any enclosing declaration carries the annotation |
 * | `extension`, `extension(fqn)` | the innermost declaration is an extension (of that type) |
 * | `context`, `context(fqn)` | the innermost declaration has a context parameter (of that type) |
 * | `visibility(v)` | the innermost declaration has that visibility |
 * | `override`, `suspend`, `inline`, `composable`, `test` | the innermost declaration is one |
 *
 * Globs use `*` for any run of characters (including dots) and `?` for one character, and
 * must match the whole value.
 */
sealed class ExcludePredicate {
    abstract fun matches(site: ReportSite): Boolean

    data class PackageIs(val glob: Glob) : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean = glob.matches(site.packageName)
    }

    data class FileIs(val glob: Glob) : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean = glob.matches(site.fileName)
    }

    data class NameIs(val glob: Glob) : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean = site.declarationName?.let(glob::matches) ?: false
    }

    data class ClassIs(val glob: Glob) : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean = site.classNames.any(glob::matches)
    }

    data class Annotated(val annotation: String) : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean = annotation in site.annotations
    }

    data class Extension(val receiver: String?) : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean =
            site.extensionReceiver != null && (receiver == null || receiver == site.extensionReceiver)
    }

    data class Context(val type: String?) : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean =
            if (type == null) site.contextParameters.isNotEmpty() else type in site.contextParameters
    }

    data class VisibilityIs(val visibility: String) : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean = site.visibility == visibility
    }

    data object Override : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean = site.isOverride
    }

    data object Suspend : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean = site.isSuspend
    }

    data object Inline : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean = site.isInline
    }

    data object Composable : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean = site.isComposable
    }

    data object Test : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean = site.isTest
    }

    data class Not(val operand: ExcludePredicate) : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean = !operand.matches(site)
    }

    data class And(val left: ExcludePredicate, val right: ExcludePredicate) : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean = left.matches(site) && right.matches(site)
    }

    data class Or(val left: ExcludePredicate, val right: ExcludePredicate) : ExcludePredicate() {
        override fun matches(site: ReportSite): Boolean = left.matches(site) || right.matches(site)
    }
}

/** `*` matches any run of characters, `?` one character; the whole value must match. */
class Glob(val pattern: String) {
    private val regex: Regex = buildString {
        for (c in pattern) {
            when (c) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(c.toString()))
            }
        }
    }.toRegex()

    fun matches(value: String): Boolean = regex.matches(value)

    override fun equals(other: Any?): Boolean = other is Glob && other.pattern == pattern
    override fun hashCode(): Int = pattern.hashCode()
    override fun toString(): String = pattern
}

package com.kitakkun.kotrail.exclude

/**
 * A resolved call, described in the terms a call predicate can ask about: what is called and
 * how. Nothing here refers to the compiler, so predicates can be evaluated and tested on their own.
 */
class CallSite(
    /** The callee's fully qualified name: `kotlin.io.println`, `java.lang.Thread.sleep`, `com.acme.Logger.debug`. */
    val fqn: String,
    /** For a constructor call, the constructed class's fully qualified name; `null` otherwise. */
    val constructedClass: String?,
    /** The callee's declared extension receiver type, fully qualified, if it is an extension. */
    val extensionReceiver: String?,
    /** The type of the receiver the call is made on (explicit, or the implicit dispatch or extension receiver), fully qualified. */
    val receiver: String?,
    /** The callee's declared context parameter types, fully qualified. */
    val contextParameters: List<String>,
    /** The callee's declared value parameter types, fully qualified, in order; a vararg is its element type. */
    val parameters: List<String>,
    /** Fully qualified annotation names on the callee. */
    val annotations: Set<String>,
    val isSuspend: Boolean,
    val isComposable: Boolean,
)

/**
 * A predicate over a [CallSite], written in the configuration as
 * `forbiddenCall[globalScope]=fqn(kotlinx.coroutines.launch) && receiver(kotlinx.coroutines.GlobalScope)`.
 *
 * | Atom | True when |
 * |---|---|
 * | `fqn(glob)` | the callee's fully qualified name matches |
 * | `constructor(glob)` | the call constructs a class whose fully qualified name matches |
 * | `extension`, `extension(fqn)` | the callee is an extension (of that receiver type) |
 * | `receiver(fqn)` | the call is made on a receiver of that type |
 * | `context`, `context(fqn)` | the callee declares a context parameter (of that type) |
 * | `params(fqn, fqn, ...)` | the callee's value parameters have exactly these types, in order |
 * | `annotated(fqn)` | the callee carries the annotation |
 * | `suspend`, `composable` | the callee is one |
 */
sealed class CallPredicate {
    abstract fun matches(site: CallSite): Boolean

    data class FqnIs(val glob: Glob) : CallPredicate() {
        override fun matches(site: CallSite): Boolean = glob.matches(site.fqn)
    }

    data class Constructs(val glob: Glob) : CallPredicate() {
        override fun matches(site: CallSite): Boolean = site.constructedClass?.let(glob::matches) ?: false
    }

    data class Extension(val receiver: String?) : CallPredicate() {
        override fun matches(site: CallSite): Boolean =
            site.extensionReceiver != null && (receiver == null || receiver == site.extensionReceiver)
    }

    data class ReceiverIs(val type: String) : CallPredicate() {
        override fun matches(site: CallSite): Boolean = site.receiver == type
    }

    data class Context(val type: String?) : CallPredicate() {
        override fun matches(site: CallSite): Boolean =
            if (type == null) site.contextParameters.isNotEmpty() else type in site.contextParameters
    }

    data class ParamsAre(val types: List<String>) : CallPredicate() {
        override fun matches(site: CallSite): Boolean = site.parameters == types
    }

    data class Annotated(val annotation: String) : CallPredicate() {
        override fun matches(site: CallSite): Boolean = annotation in site.annotations
    }

    data object Suspend : CallPredicate() {
        override fun matches(site: CallSite): Boolean = site.isSuspend
    }

    data object Composable : CallPredicate() {
        override fun matches(site: CallSite): Boolean = site.isComposable
    }

    /** A named predicate from the configuration's `predicates`, standing for [expansion]; rendered as `name (= expansion)`. */
    data class Alias(val name: String, val expansion: CallPredicate) : CallPredicate() {
        override fun matches(site: CallSite): Boolean = expansion.matches(site)
        override fun toString(): String = "$name (= $expansion)"
    }

    data class Not(val operand: CallPredicate) : CallPredicate() {
        override fun matches(site: CallSite): Boolean = !operand.matches(site)
    }

    data class And(val left: CallPredicate, val right: CallPredicate) : CallPredicate() {
        override fun matches(site: CallSite): Boolean = left.matches(site) && right.matches(site)
    }

    data class Or(val left: CallPredicate, val right: CallPredicate) : CallPredicate() {
        override fun matches(site: CallSite): Boolean = left.matches(site) || right.matches(site)
    }
}

/** Parses a [CallPredicate]; the grammar is [PredicateGrammar]'s. */
object CallPredicateParser {
    /** The built-in atoms; a named predicate may not take one of these names. */
    val ATOM_NAMES: Set<String> = setOf("fqn", "constructor", "extension", "receiver", "context", "params", "annotated", "suspend", "composable")

    fun parse(text: String, aliases: Map<String, String> = emptyMap()): CallPredicate = PredicateGrammar.parse(text, ATOMS, aliases)

    private val ATOMS = PredicateGrammar.Atoms(
        atom = ::atom,
        not = CallPredicate::Not,
        and = CallPredicate::And,
        or = CallPredicate::Or,
        alias = CallPredicate::Alias,
    )

    private fun atom(name: String, argument: String?, fail: (String) -> Nothing): CallPredicate {
        fun required(): String = argument?.takeIf { it.isNotEmpty() }
            ?: fail("'$name' needs an argument, as in $name(...)")
        fun none() {
            if (argument != null) fail("'$name' takes no argument")
        }
        return when (name) {
            "fqn" -> CallPredicate.FqnIs(Glob(required()))
            "constructor" -> CallPredicate.Constructs(Glob(required()))
            "extension" -> CallPredicate.Extension(argument?.takeIf { it.isNotEmpty() })
            "receiver" -> CallPredicate.ReceiverIs(required())
            "context" -> CallPredicate.Context(argument?.takeIf { it.isNotEmpty() })
            "params" -> CallPredicate.ParamsAre(
                argument.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            )
            "annotated" -> CallPredicate.Annotated(required())
            "suspend" -> { none(); CallPredicate.Suspend }
            "composable" -> { none(); CallPredicate.Composable }
            else -> fail("unknown call predicate '$name'")
        }
    }
}

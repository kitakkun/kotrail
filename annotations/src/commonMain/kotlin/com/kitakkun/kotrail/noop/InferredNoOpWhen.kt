package com.kitakkun.kotrail.noop

/**
 * Written by the Kotrail compiler plugin, not by hand: the conditions under which a function that
 * returns `Unit` returns at once without doing anything, read from the `if (condition) return`
 * guards that open its body and rendered in the expression language of
 * [com.kitakkun.kotrail.preconditions.InferredPreconditions], so that call sites in other modules
 * whose arguments make one of them true can be reported at compile time.
 *
 * Only conditions that mention nothing but parameters and constants are recorded, and only for a
 * function no override can replace.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class InferredNoOpWhen(vararg val conditions: String)

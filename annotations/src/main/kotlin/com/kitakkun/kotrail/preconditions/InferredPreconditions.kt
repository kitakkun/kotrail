package com.kitakkun.kotrail.preconditions

/**
 * Written by the Kotrail compiler plugin, not by hand: the preconditions (`require`, `check`,
 * `requireNotNull`) a function or a class's `init` blocks impose on their parameters, rendered
 * in a small expression language so that call sites in other modules can be checked against
 * them at compile time.
 *
 * Only conditions that mention nothing but parameters and constants are recorded; anything
 * else is left out and simply not verified.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.BINARY)
annotation class InferredPreconditions(vararg val conditions: String)

package com.kitakkun.kotrail.compose.locals

/**
 * Written by the Kotrail compiler plugin onto composables. Records which composition locals
 * the composable reads, directly or through what it calls, without providing them itself
 * ([reads], fully qualified property names, required or not: whether a read is an error is
 * decided at the root with the project's settings) and which locals it provides to each of its
 * composable lambda parameters ([provides], entries of the form
 * `<parameter>:<fully qualified property name>`), so that callers in other modules can be
 * verified. Never write this annotation by hand.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class InferredCompositionLocals(val reads: Array<String>, val provides: Array<String>)

/**
 * Written by the Kotrail compiler plugin onto composition local properties whose default
 * factory throws, so that modules compiled against this one know the local must be provided.
 * Never write this annotation by hand.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class InferredRequiredCompositionLocal

package com.kitakkun.kotrail.errors

/**
 * Written by the Kotrail compiler plugin onto a function whose whole body is one `try` whose
 * `Throwable` or `Exception` clause hands the exception whole to whoever records it (an
 * assignment, a call) and then rethrows it: every failure of the function is recorded before it
 * reaches the caller. Callers in other modules may then catch it broadly with an empty clause.
 * Not meant to be written by hand.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class InferredRecordsFailure

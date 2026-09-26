package com.kitakkun.kotrail.concurrency

/**
 * Written by the Kotrail compiler plugin onto a non-suspending function whose body starts
 * asynchronous work (a coroutine, a thread, a posted runnable) and does not hand its caller a
 * way to wait for it: the work outlives the call. Callers in other modules are checked against
 * it. Not meant to be written by hand.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class InferredStartsAsyncWork

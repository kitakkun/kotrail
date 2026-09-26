package com.kitakkun.kotrail.memory

/**
 * Written by the Kotrail compiler plugin onto a non-private function whose body creates a
 * native-backed object (`nativeAllocationInLoop.types` and `factories`) and lets it out
 * unclosed, directly or through a function that does: [types] names what it creates. Callers
 * in other modules that call it once per iteration are checked against it. Not meant to be
 * written by hand.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class InferredNativeAllocation(val types: Array<String>)

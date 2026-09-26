package com.kitakkun.kotrail.memory

/**
 * Written by the Kotrail compiler plugin onto a non-private function whose body creates a
 * native-backed object (`nativeAllocationInLoop.types` and `factories`) and lets it out
 * unclosed, directly or through a function that does. [types] holds the type it creates and,
 * deliberately as its second entry rather than a parameter of its own, what the function does
 * with it (`returns`, `keeps`, `lets go of`); [path] names the functions it went through to
 * create it, outermost first, members qualified by their class. Callers in other modules
 * that call it once per iteration are checked against it. Not meant to be written by hand.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class InferredNativeAllocation(val types: Array<String>, val path: Array<String>)

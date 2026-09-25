package com.kitakkun.kotrail.lifetime

/**
 * A parameter the function must not retain: it may read it, call it, and register callbacks on
 * it, but it must not store it in a property, a collection, or a lambda that outlives the call,
 * except through a weak reference. Kotrail's `unretained` rule checks the function's body.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Unretained

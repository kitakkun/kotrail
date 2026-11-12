package com.kitakkun.kotrail.idempotency

/**
 * A function, or a property setter, that is idempotent: calling it twice in a row with the same
 * arguments has the same effect as calling it once, so a retry, a duplicate event or a second
 * `refresh()` is harmless. Kotrail's `idempotent` rule checks the body for writes that undo the
 * promise: a read-modify-write of state outside the function (`count++`, `total += x`), an
 * append to a list, a send or an emit, a launched coroutine, or a time, random or UUID value
 * stored outside the function.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Idempotent

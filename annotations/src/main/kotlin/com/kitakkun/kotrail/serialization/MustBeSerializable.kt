package com.kitakkun.kotrail.serialization

/**
 * Declares that a type argument or an argument must be serializable with kotlinx.serialization.
 *
 * On a type parameter, every call site's (explicit or inferred) type argument for that parameter is
 * checked; on a value parameter, the argument's type is checked. A type is serializable when it is
 * a primitive, `String`, `Unit`, an enum, a standard collection or array of serializable types, or
 * a class annotated with `@kotlinx.serialization.Serializable`. Kotrail reports
 * `KOTRAIL_TYPE_NOT_SERIALIZABLE` otherwise, so that a `serializer<T>()` lookup that would fail at runtime
 * fails at compile time instead.
 */
@Target(AnnotationTarget.TYPE_PARAMETER, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
annotation class MustBeSerializable

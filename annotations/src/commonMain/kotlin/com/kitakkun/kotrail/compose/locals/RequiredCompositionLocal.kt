package com.kitakkun.kotrail.compose.locals

/**
 * Marks a composition local property as one that must be provided before it is read, even
 * though its default factory does not throw. Locals whose default throws are required without
 * this annotation.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class RequiredCompositionLocal

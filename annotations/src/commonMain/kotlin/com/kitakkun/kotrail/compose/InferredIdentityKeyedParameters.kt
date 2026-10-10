package com.kitakkun.kotrail.compose

/**
 * Written by the Kotrail compiler plugin onto a composable that keys a `remember` (or
 * `rememberSaveable`) on some of its parameters and computes the remembered value from them:
 * `remember(items) { items.filter { ... } }`, directly or by handing the parameter to another
 * composable that does. `remember` compares keys by identity, so a caller that passes a value
 * which changes in place (a `SnapshotStateList`, a `State`) gets a remembered copy that goes
 * stale. Callers in other modules are checked against it. Not meant to be written by hand.
 *
 * An entry of [names] is a parameter's name, followed, when the parameter is handed on to the
 * composable that remembers the copy, by `@` and that composable's name (`transactions@TrafficTab`).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class InferredIdentityKeyedParameters(val names: Array<String>)

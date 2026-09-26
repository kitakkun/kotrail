package com.kitakkun.kotrail.compose.effects

/**
 * Written by the Kotrail compiler plugin onto a composable whose body captures some of its
 * parameters in a long-lived effect (a `collect`, a loop, `onDispose`) without reading them
 * through `rememberUpdatedState` or keying the effect on them: a lambda a caller passes for such
 * a parameter is kept for the life of the effect, so what that lambda reads goes stale. Callers
 * in other modules are checked against it. Not meant to be written by hand.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class InferredEffectCapture(val captured: Array<String>)

package com.kitakkun.kotrail.compose

/**
 * Written by the Kotrail compiler plugin onto a `Unit`-returning composable whose body puts
 * nothing on the screen (a wrapper around an effect, a probe that only reads a local, a tracker
 * that only registers state), as `value = ["false"]`. Callers in other modules are judged by it:
 * a composable that calls only such ones draws nothing either, and needs no preview. A composable
 * without it is taken to draw. Not meant to be written by hand.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class InferredDrawsUi(val value: Array<String>)

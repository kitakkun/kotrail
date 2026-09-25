package com.kitakkun.kotrail.compose.locals

/**
 * Marks a composable as a root of composition: nothing above it provides composition locals,
 * so every required local read anywhere below it must be provided by this function's body or
 * by the composables it calls. The Kotrail compiler plugin reports each required local that is
 * read below the root and never provided, with the call path that reads it.
 *
 * `@Preview` functions and the content lambdas of the entry points listed in
 * `compose.compositionLocals.roots` (`setContent`, `Window`, ...) are roots without this
 * annotation.
 *
 * A composition local is *required* when its default factory throws
 * (`compositionLocalOf { error("...") }`), when its property carries [RequiredCompositionLocal],
 * or when the project lists it in `compose.compositionLocals.required`. A local with a real
 * default never has to be provided.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class CompositionLocalRoot

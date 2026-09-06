package com.kitakkun.kotrail.compose.insets

/**
 * Declares that the annotated composable handles the given window insets internally, so callers
 * must not apply them again.
 *
 * Kotrail verifies the contract at compile time: if the body (including the composables it
 * calls, across modules) does not handle every declared inset on every declared side, the build
 * fails with `WINDOW_INSETS_NOT_HANDLED`.
 *
 * [sides] defaults to all sides because that is what the vast majority of contracts mean; the
 * default makes the common case readable and the exception explicit.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Repeatable
@MustBeDocumented
annotation class HandlesWindowInsets(
    val type: WindowInsetsType,
    val sides: Array<WindowInsetsSide> = [WindowInsetsSide.All],
)

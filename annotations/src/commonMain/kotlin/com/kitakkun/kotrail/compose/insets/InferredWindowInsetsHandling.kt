package com.kitakkun.kotrail.compose.insets

/**
 * Written by the Kotrail compiler plugin onto composables that carry no [HandlesWindowInsets]
 * contract. Records which insets the composable was found to handle so that callers in other
 * modules can be verified. Never write this annotation by hand.
 *
 * Each entry is `<primitiveType>:<sides>` where sides is a subset of the letters `tblrLR` in
 * that order: top, bottom, left in LTR, right in LTR, left in RTL, right in RTL. The four
 * horizontal bits mirror `WindowInsetsSides`, so `Start` encodes as `lR`, `Left` as `lL`, and
 * `Horizontal` as `lrLR`. Examples: `statusBars:t`, `ime:tblrLR`.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class InferredWindowInsetsHandling(val handled: Array<String>)

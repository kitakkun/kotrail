package com.kitakkun.kotrail.compose.insets

/**
 * Window insets families, mirroring `androidx.compose.foundation.layout.WindowInsets.Companion`.
 *
 * Composite entries expand to unions of primitive ones when the plugin compares contracts:
 * - [SystemBars] = [StatusBars] + [NavigationBars] + [CaptionBar]
 * - [SafeDrawing] = [SystemBars] + [DisplayCutout] + [Ime]
 * - [SafeGestures] = [SystemGestures] + [MandatorySystemGestures] + [TappableElement] + [Waterfall]
 * - [SafeContent] = [SafeDrawing] + [SafeGestures]
 */
enum class WindowInsetsType {
    StatusBars,
    NavigationBars,
    CaptionBar,
    SystemBars,
    DisplayCutout,
    Ime,
    SafeDrawing,
    SystemGestures,
    MandatorySystemGestures,
    TappableElement,
    Waterfall,
    SafeGestures,
    SafeContent,
}

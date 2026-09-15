// Stubs mirroring androidx.compose.foundation.layout. Signatures (including default values)
// follow the real API so that test data reads like production code.
package androidx.compose.foundation.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

interface WindowInsets {
    companion object
}

class StubInsets(val name: String) : WindowInsets

fun WindowInsets(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0): WindowInsets = StubInsets("fixed($left,$top,$right,$bottom)")

val WindowInsets.Companion.statusBars: WindowInsets get() = StubInsets("statusBars")
val WindowInsets.Companion.navigationBars: WindowInsets get() = StubInsets("navigationBars")
val WindowInsets.Companion.captionBar: WindowInsets get() = StubInsets("captionBar")
val WindowInsets.Companion.systemBars: WindowInsets get() = StubInsets("systemBars")
val WindowInsets.Companion.displayCutout: WindowInsets get() = StubInsets("displayCutout")
val WindowInsets.Companion.ime: WindowInsets get() = StubInsets("ime")
val WindowInsets.Companion.safeDrawing: WindowInsets get() = StubInsets("safeDrawing")
val WindowInsets.Companion.systemGestures: WindowInsets get() = StubInsets("systemGestures")
val WindowInsets.Companion.mandatorySystemGestures: WindowInsets get() = StubInsets("mandatorySystemGestures")
val WindowInsets.Companion.tappableElement: WindowInsets get() = StubInsets("tappableElement")
val WindowInsets.Companion.waterfall: WindowInsets get() = StubInsets("waterfall")
val WindowInsets.Companion.safeGestures: WindowInsets get() = StubInsets("safeGestures")
val WindowInsets.Companion.safeContent: WindowInsets get() = StubInsets("safeContent")

@JvmInline
value class WindowInsetsSides(val value: Int) {
    operator fun plus(sides: WindowInsetsSides): WindowInsetsSides = WindowInsetsSides(value or sides.value)

    companion object {
        val Top = WindowInsetsSides(1)
        val Bottom = WindowInsetsSides(2)
        val Left = WindowInsetsSides(4)
        val Right = WindowInsetsSides(8)
        val Start = Left
        val End = Right
        val Horizontal = Left + Right
        val Vertical = Top + Bottom
    }
}

fun WindowInsets.only(sides: WindowInsetsSides): WindowInsets = this
fun WindowInsets.union(insets: WindowInsets): WindowInsets = this
fun WindowInsets.add(insets: WindowInsets): WindowInsets = this
fun WindowInsets.exclude(insets: WindowInsets): WindowInsets = this

class PaddingValues

@Composable
fun WindowInsets.asPaddingValues(): PaddingValues = PaddingValues()

fun Modifier.padding(paddingValues: PaddingValues): Modifier = this
fun Modifier.windowInsetsPadding(insets: WindowInsets): Modifier = this
fun Modifier.consumeWindowInsets(insets: WindowInsets): Modifier = this
fun Modifier.consumeWindowInsets(paddingValues: PaddingValues): Modifier = this
fun Modifier.windowInsetsTopHeight(insets: WindowInsets): Modifier = this
fun Modifier.windowInsetsBottomHeight(insets: WindowInsets): Modifier = this
fun Modifier.windowInsetsStartWidth(insets: WindowInsets): Modifier = this
fun Modifier.windowInsetsEndWidth(insets: WindowInsets): Modifier = this

fun Modifier.safeDrawingPadding(): Modifier = this
fun Modifier.safeContentPadding(): Modifier = this
fun Modifier.safeGesturesPadding(): Modifier = this
fun Modifier.systemBarsPadding(): Modifier = this
fun Modifier.statusBarsPadding(): Modifier = this
fun Modifier.navigationBarsPadding(): Modifier = this
fun Modifier.imePadding(): Modifier = this
fun Modifier.displayCutoutPadding(): Modifier = this
fun Modifier.captionBarPadding(): Modifier = this
fun Modifier.waterfallPadding(): Modifier = this
fun Modifier.systemGesturesPadding(): Modifier = this
fun Modifier.mandatorySystemGesturesPadding(): Modifier = this

@Composable
fun Box(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    content()
}

@Composable
fun Column(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    content()
}

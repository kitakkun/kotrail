// KOTRAIL_CONFIG: rules.compose.previewRequired=on
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

// A multipreview annotation: itself annotated with @Preview.
@Preview(name = "light")
@Preview(name = "dark")
annotation class ThemePreviews

// Reported: a public UI composable with no preview in this file.
@Composable
fun <!KOTRAIL_COMPOSABLE_WITHOUT_PREVIEW!>Orphan<!>(modifier: Modifier = Modifier) {
    Box(modifier) { Text("orphan") }
}

// Reported: internal composables need one too (default scope).
@Composable
internal fun <!KOTRAIL_COMPOSABLE_WITHOUT_PREVIEW!>InternalOrphan<!>(modifier: Modifier = Modifier) {
    Box(modifier) { Text("internal") }
}

// Not reported: previewed by a @Preview function below.
@Composable
fun Card(title: String, modifier: Modifier = Modifier) {
    Box(modifier) { Text(title) }
}

@Preview
@Composable
private fun CardPreview() {
    Card(title = "Ada")
}

// Not reported: previewed through a multipreview annotation.
@Composable
fun Badge(count: Int, modifier: Modifier = Modifier) {
    Box(modifier) { Text("$count") }
}

@ThemePreviews
@Composable
private fun BadgePreview() {
    Badge(count = 3)
}

// Not reported: private helpers, value-returning composables, and non-composables.
@Composable
private fun Helper() {
    Text("helper")
}

@Composable
fun rememberLabel(): String = "label"

fun plain() {}

// Not reported: overloads are one component; a preview calling either covers both.
@Composable
fun Chip(label: String, modifier: Modifier = Modifier) {
    Box(modifier) { Text(label) }
}

@Composable
fun Chip(count: Int, modifier: Modifier = Modifier) {
    Box(modifier) { Text("$count") }
}

@Preview
@Composable
private fun ChipPreview() {
    Chip(label = "chip")
}

// Not reported: suppressed on the declaration itself.
@Suppress("KOTRAIL_COMPOSABLE_WITHOUT_PREVIEW")
@Composable
fun SuppressedOrphan(modifier: Modifier = Modifier) {
    Box(modifier) { Text("suppressed") }
}

// Not reported: suppressed on the class around it.
@Suppress("KOTRAIL_COMPOSABLE_WITHOUT_PREVIEW")
object SuppressedWidgets {
    @Composable
    fun Chip(label: String) {
        Text(label)
    }
}

// Not reported: an abstract member has nothing to render.
interface Slot {
    @Composable
    fun Content()
}

// Not reported: composables that draw nothing: an effect wrapper, a wrapper around it, a tracker.
@Composable
fun TrackScreen(name: String) {
    LaunchedEffect(name) { println(name) }
}

@Composable
fun TrackHome() {
    TrackScreen("home")
}

@Composable
fun RememberOnly(count: Int) {
    val doubled = remember { count * 2 }
    println(doubled)
}

// Reported: a provider around a content slot draws whatever the slot draws.
@Composable
fun <!KOTRAIL_COMPOSABLE_WITHOUT_PREVIEW!>WithLocals<!>(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCount provides 1) { content() }
}

val LocalCount = compositionLocalOf { 0 }

// Reported: invoking a content slot draws whatever the slot draws, directly or inside a layout.
@Composable
fun <!KOTRAIL_COMPOSABLE_WITHOUT_PREVIEW!>SlotDirect<!>(content: @Composable () -> Unit) {
    content()
}

@Composable
fun <!KOTRAIL_COMPOSABLE_WITHOUT_PREVIEW!>SlotInLambda<!>(content: @Composable () -> Unit) {
    Box { content() }
}

// Reported: a wrapper that ends in a drawing composable draws.
@Composable
fun <!KOTRAIL_COMPOSABLE_WITHOUT_PREVIEW!>Labelled<!>(label: String) {
    TrackScreen(label)
    Text(label)
}

// Not reported: an object member previewed next to it.
object Widgets {
    @Composable
    fun Chip(label: String) {
        Text(label)
    }

    @Preview
    @Composable
    private fun ChipPreview() {
        Chip(label = "chip")
    }
}

/* GENERATED_FIR_TAGS: annotationDeclaration, functionDeclaration, integerLiteral, lambdaLiteral, objectDeclaration,
stringLiteral */

// KOTRAIL_CONFIG: rules.compose.previewRequired=true
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

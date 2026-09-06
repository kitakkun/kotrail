// KOTRAIL_CONFIG: rules.compose.previewRequired=false
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

// Default limit: 3 non-private UI composables per file, previews and private helpers excluded.

@Composable
fun First(modifier: Modifier = Modifier) {
    Box(modifier) { Text("1") }
}

@Composable
internal fun Second(modifier: Modifier = Modifier) {
    Box(modifier) { Text("2") }
}

object Grouped {
    @Composable
    fun Third(modifier: Modifier = Modifier) {
        Box(modifier) { Text("3") }
    }
}

// Reported: the fourth and fifth non-private composables in this file.
@Composable
fun <!TOO_MANY_COMPOSABLES_IN_FILE!>Fourth<!>(modifier: Modifier = Modifier) {
    Box(modifier) { Text("4") }
}

@Composable
fun <!TOO_MANY_COMPOSABLES_IN_FILE!>Fifth<!>(modifier: Modifier = Modifier) {
    Box(modifier) { Text("5") }
}

// Not counted: private helpers, previews, value-returning composables.
@Composable
private fun Helper() {
    Text("helper")
}

@Preview
@Composable
private fun FirstPreview() {
    First()
}

@Composable
fun rememberLabel(): String = "label"

/* GENERATED_FIR_TAGS: functionDeclaration, lambdaLiteral, objectDeclaration, stringLiteral */

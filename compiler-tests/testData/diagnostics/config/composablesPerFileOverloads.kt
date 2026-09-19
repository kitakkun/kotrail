// KOTRAIL_CONFIG: rules.compose.composablesPerFile=on, rules.compose.composablesPerFile.countOverloadsSeparately=true
// With overloads counted separately, the second First is a component of its own and the file holds four.
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun First(modifier: Modifier = Modifier) {
    Box(modifier) { Text("1") }
}

@Composable
fun First(label: String, modifier: Modifier = Modifier) {
    Box(modifier) { Text(label) }
}

@Composable
fun Second(modifier: Modifier = Modifier) {
    Box(modifier) { Text("2") }
}

// Reported: the fourth counted composable.
@Composable
fun <!KOTRAIL_TOO_MANY_COMPOSABLES_IN_FILE!>Third<!>(modifier: Modifier = Modifier) {
    Box(modifier) { Text("3") }
}

/* GENERATED_FIR_TAGS: functionDeclaration, lambdaLiteral, stringLiteral */

// KOTRAIL_CONFIG: rules.compose.previewRequired=on, rules.compose.previewRequired.exclude=name(*Root) || class(Legacy*)
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Reported: no preview, and no predicate matches.
@Composable
fun <!KOTRAIL_COMPOSABLE_WITHOUT_PREVIEW!>Orphan<!>(modifier: Modifier = Modifier) {
    Box(modifier) { Text("orphan") }
}

// Not reported: the declaration's own name matches name(*Root), although the checker runs on the file.
@Composable
fun SettingsRoot(modifier: Modifier = Modifier) {
    Box(modifier) { Text("root") }
}

object LegacyScreens {
    // Not reported: an enclosing class matches class(Legacy*).
    @Composable
    fun Old(modifier: Modifier = Modifier) {
        Box(modifier) { Text("old") }
    }
}

/* GENERATED_FIR_TAGS: functionDeclaration, lambdaLiteral, objectDeclaration, stringLiteral */

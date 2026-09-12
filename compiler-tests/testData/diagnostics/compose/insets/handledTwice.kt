// KOTRAIL_CONFIG: rules.compose.windowInsets=true, rules.compose.windowInsetsHandledTwice=true
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun TopBarArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))) {
        content()
    }
}

@Composable
fun Screen() {
    // Overlap: TopBarArea handles status bars (top); safeDrawingPadding includes them.
    <!KOTRAIL_WINDOW_INSETS_HANDLED_TWICE!>TopBarArea(modifier = Modifier.safeDrawingPadding()) { Text("twice") }<!>

    // No overlap: the IME is not handled by TopBarArea.
    TopBarArea(modifier = Modifier.imePadding()) { Text("fine") }

    // Overlap through the knowledge base: Scaffold handles system bars.
    <!KOTRAIL_WINDOW_INSETS_HANDLED_TWICE!>Scaffold(modifier = Modifier.safeDrawingPadding()) { _ -> Text("twice") }<!>
}

/* GENERATED_FIR_TAGS: functionDeclaration, functionalType, lambdaLiteral, stringLiteral */

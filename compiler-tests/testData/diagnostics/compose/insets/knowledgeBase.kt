// KOTRAIL_CONFIG: rules.compose.windowInsets=true, compose.windowInsets.known=custom.AppScaffold=SystemBars, compose.windowInsets.known=custom.AppTopBar=StatusBars:Top+Horizontal, compose.windowInsets.known=androidx.compose.material3.Scaffold=None

package custom

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.kitakkun.kotrail.compose.insets.HandlesWindowInsets
import com.kitakkun.kotrail.compose.insets.WindowInsetsSide
import com.kitakkun.kotrail.compose.insets.WindowInsetsType

// The project's own scaffold. Its body handles nothing that the analysis can see (a real one
// would do it through a platform view or a library); the configuration says what it handles.
@Composable
fun AppScaffold(windowInsets: WindowInsets = WindowInsets.systemBars, content: @Composable () -> Unit) {
    content()
}

@Composable
fun AppTopBar() {
    Text("title")
}

// Satisfied through the configured knowledge: AppScaffold handles the system bars.
@HandlesWindowInsets(WindowInsetsType.SystemBars)
@Composable
fun HomeScreen() {
    AppScaffold { Text("home") }
}

// Reported: the knowledge says system bars only; display cutout and IME are still missing.
@HandlesWindowInsets(WindowInsetsType.SafeDrawing)
@Composable
fun <!WINDOW_INSETS_NOT_HANDLED!>SafeScreen<!>() {
    AppScaffold { Text("safe") }
}

// Satisfied: the top bar covers the top side of the status bars.
@HandlesWindowInsets(WindowInsetsType.StatusBars, sides = [WindowInsetsSide.Top])
@Composable
fun TopScreen() {
    AppTopBar()
}

// Reported: the bottom side of the status bars is not in the top bar's entry.
@HandlesWindowInsets(WindowInsetsType.StatusBars)
@Composable
fun <!WINDOW_INSETS_NOT_HANDLED!>AllSidesScreen<!>() {
    AppTopBar()
}

// Satisfied: a WindowInsets argument passed to a known composable replaces its entry.
@HandlesWindowInsets(WindowInsetsType.Ime)
@Composable
fun ImeScreen() {
    AppScaffold(windowInsets = WindowInsets.ime) { Text("ime") }
}

// Reported: the built-in entry for Material's Scaffold was replaced with None.
@HandlesWindowInsets(WindowInsetsType.SystemBars)
@Composable
fun <!WINDOW_INSETS_NOT_HANDLED!>MaterialScreen<!>() {
    Scaffold { Text("material") }
}

/* GENERATED_FIR_TAGS: collectionLiteral, functionDeclaration, functionalType, lambdaLiteral, stringLiteral */

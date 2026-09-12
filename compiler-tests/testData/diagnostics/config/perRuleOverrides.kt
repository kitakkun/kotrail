// KOTRAIL_CONFIG: rules.compose.nesting=true, compose.nesting.maxDepth=2, rules.compose.windowInsets=true, rules.compose.windowInsetsHandledTwice=false
// The nesting limit is lowered to 2 while the double-handling warning alone is switched off;
// the contract check stays on.
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kitakkun.kotrail.compose.insets.HandlesWindowInsets
import com.kitakkun.kotrail.compose.insets.WindowInsetsType

// Depth 3 exceeds the lowered limit of 2.
@Composable
fun Deep() {
    Column { Box { <!KOTRAIL_COMPOSABLE_NESTING_TOO_DEEP!>Text<!>("three") } }
}

@Composable
fun TopBarArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.statusBarsPadding()) { content() }
}

// Not reported: the double-handling warning is off.
@Composable
fun DoublePadded() {
    TopBarArea(modifier = Modifier.safeDrawingPadding()) { Text("twice") }
}

// Still reported: the contract check is on.
@HandlesWindowInsets(WindowInsetsType.SafeDrawing)
@Composable
fun <!KOTRAIL_WINDOW_INSETS_NOT_HANDLED!>MissingScreen<!>() {
    Column(modifier = Modifier.statusBarsPadding()) { Text("missing") }
}

/* GENERATED_FIR_TAGS: functionDeclaration, functionalType, lambdaLiteral, stringLiteral */

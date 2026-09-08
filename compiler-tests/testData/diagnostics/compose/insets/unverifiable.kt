// KOTRAIL_CONFIG: rules.compose.windowInsets=true
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kitakkun.kotrail.compose.insets.HandlesWindowInsets
import com.kitakkun.kotrail.compose.insets.WindowInsetsType

// The insets come from a parameter, so the contract cannot be proven: warning, not error.
@HandlesWindowInsets(WindowInsetsType.SafeDrawing)
@Composable
fun <!WINDOW_INSETS_HANDLING_UNVERIFIABLE!>DynamicScreen<!>(insets: WindowInsets) {
    Column(modifier = Modifier.windowInsetsPadding(insets)) { Text("dynamic") }
}

// An unknown expression does not matter when the contract is already satisfied statically.
@HandlesWindowInsets(WindowInsetsType.StatusBars)
@Composable
fun MixedScreen(insets: WindowInsets) {
    Column(modifier = Modifier.statusBarsPadding().windowInsetsPadding(insets)) { Text("mixed") }
}

/* GENERATED_FIR_TAGS: functionDeclaration, lambdaLiteral, stringLiteral */

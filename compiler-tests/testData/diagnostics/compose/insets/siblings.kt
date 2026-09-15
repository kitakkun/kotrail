// KOTRAIL_CONFIG: rules.compose.windowInsets=true, rules.compose.windowInsetsHandledTwice=true
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.captionBarPadding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kitakkun.kotrail.compose.insets.HandlesWindowInsets
import com.kitakkun.kotrail.compose.insets.WindowInsetsSide
import com.kitakkun.kotrail.compose.insets.WindowInsetsType

// Insets handled piece by piece on sibling elements, each taking its own share. The contract is
// the union of what the body handles, and no sibling overlaps another, so nothing is reported.

// Satisfied: status bars on the header, IME on the composer, navigation bars on the footer.
@HandlesWindowInsets(WindowInsetsType.StatusBars, sides = [WindowInsetsSide.Top])
@HandlesWindowInsets(WindowInsetsType.Ime)
@HandlesWindowInsets(WindowInsetsType.NavigationBars, sides = [WindowInsetsSide.Bottom])
@Composable
fun ChatScreen() {
    Column {
        Box(modifier = Modifier.statusBarsPadding()) { Text("header") }
        Box(modifier = Modifier.imePadding()) { Text("composer") }
        Box(modifier = Modifier.navigationBarsPadding()) { Text("footer") }
    }
}

// Satisfied: a composite contract collected from four siblings.
@HandlesWindowInsets(WindowInsetsType.SafeDrawing)
@Composable
fun SplitScreen() {
    Column {
        Box(modifier = Modifier.statusBarsPadding().captionBarPadding()) { Text("top") }
        Box(modifier = Modifier.displayCutoutPadding()) { Text("cutout") }
        Box(modifier = Modifier.imePadding()) { Text("middle") }
        Box(modifier = Modifier.navigationBarsPadding()) { Text("bottom") }
    }
}

// Not reported as handled twice: the siblings' modifiers go to Box and Column, which handle
// nothing themselves, and a container's own padding is never compared with what its children do.
@HandlesWindowInsets(WindowInsetsType.SystemBars)
@Composable
fun ContainerScreen() {
    Column(modifier = Modifier.systemBarsPadding()) {
        TopAppBar(title = { Text("title") })
        Text("body")
    }
}

/* GENERATED_FIR_TAGS: collectionLiteral, functionDeclaration, lambdaLiteral, stringLiteral */

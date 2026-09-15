// KOTRAIL_CONFIG: rules.compose.windowInsets=true
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kitakkun.kotrail.compose.insets.HandlesWindowInsets
import com.kitakkun.kotrail.compose.insets.WindowInsetsSide
import com.kitakkun.kotrail.compose.insets.WindowInsetsType

// Not satisfied, and not merely unverifiable: WindowInsets(0) is fixed insets, so the scaffold
// handles nothing and the contract is known to be broken.
@HandlesWindowInsets(WindowInsetsType.SystemBars)
@Composable
fun <!KOTRAIL_WINDOW_INSETS_NOT_HANDLED!>OptedOutScaffold<!>() {
    Scaffold(contentWindowInsets = WindowInsets(0)) { _ -> Text("no insets") }
}

// Not satisfied: fixed padding is not inset handling either.
@HandlesWindowInsets(WindowInsetsType.StatusBars)
@Composable
fun <!KOTRAIL_WINDOW_INSETS_NOT_HANDLED!>FixedPadding<!>() {
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets(top = 24))) { Text("fixed") }
}

// Satisfied: the Material default spelled out is the system bars.
@HandlesWindowInsets(WindowInsetsType.SystemBars)
@Composable
fun ExplicitDefault() {
    Scaffold(contentWindowInsets = ScaffoldDefaults.contentWindowInsets) { _ -> Text("default") }
}

// Satisfied: the top app bar default covers the top of the system bars.
@HandlesWindowInsets(WindowInsetsType.StatusBars, sides = [WindowInsetsSide.Top])
@Composable
fun ExplicitTopBar() {
    TopAppBar(title = { Text("title") }, windowInsets = TopAppBarDefaults.windowInsets)
}

// Satisfied: the default with the IME taken out still covers the system bars.
@HandlesWindowInsets(WindowInsetsType.SystemBars)
@Composable
fun DefaultMinusIme() {
    Scaffold(contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.ime)) { _ -> Text("no ime") }
}

/* GENERATED_FIR_TAGS: collectionLiteral, functionDeclaration, integerLiteral, lambdaLiteral, stringLiteral */

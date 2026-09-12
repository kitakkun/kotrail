// KOTRAIL_CONFIG: rules.compose.windowInsets=true
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kitakkun.kotrail.compose.insets.HandlesWindowInsets
import com.kitakkun.kotrail.compose.insets.WindowInsetsSide
import com.kitakkun.kotrail.compose.insets.WindowInsetsType

// Satisfied: shorthand covers the whole composite.
@HandlesWindowInsets(WindowInsetsType.SafeDrawing)
@Composable
fun ShorthandScreen() {
    Column(modifier = Modifier.safeDrawingPadding()) { Text("ok") }
}

// Satisfied: only() with a combined sides expression.
@HandlesWindowInsets(WindowInsetsType.DisplayCutout, sides = [WindowInsetsSide.Horizontal])
@Composable
fun OnlyScreen() {
    Column(
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.displayCutout.only(WindowInsetsSides.Left + WindowInsetsSides.Right),
        ),
    ) { Text("ok") }
}

// Satisfied: union and exclude are evaluated.
@HandlesWindowInsets(WindowInsetsType.StatusBars)
@HandlesWindowInsets(WindowInsetsType.NavigationBars)
@Composable
fun UnionExcludeScreen() {
    Column(
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.exclude(WindowInsets.ime).union(WindowInsets.statusBars),
        ),
    ) { Text("ok") }
}

// Satisfied: the window insets size modifier handles exactly one side.
@HandlesWindowInsets(WindowInsetsType.StatusBars, sides = [WindowInsetsSide.Top])
@Composable
fun SpacerScreen() {
    Column(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) { Text("ok") }
}

// Satisfied: handling inside a content lambda counts, and so does a same-module callee.
@HandlesWindowInsets(WindowInsetsType.Ime)
@Composable
fun LambdaScreen() {
    Column {
        ImeAware()
    }
}

@Composable
fun ImeAware() {
    Column(modifier = Modifier.imePadding()) { Text("ok") }
}

// Satisfied: library knowledge base (Scaffold handles system bars by default).
@HandlesWindowInsets(WindowInsetsType.SystemBars)
@Composable
fun ScaffoldScreen() {
    Scaffold { _ -> Text("ok") }
}

// Not satisfied: only status bars are handled out of the whole safe-drawing set.
@HandlesWindowInsets(WindowInsetsType.SafeDrawing)
@Composable
fun <!KOTRAIL_WINDOW_INSETS_NOT_HANDLED!>MissingScreen<!>() {
    Column(modifier = Modifier.statusBarsPadding()) { Text("missing") }
}

// Not satisfied: the declared side is not the handled side.
@HandlesWindowInsets(WindowInsetsType.NavigationBars, sides = [WindowInsetsSide.Bottom])
@Composable
fun <!KOTRAIL_WINDOW_INSETS_NOT_HANDLED!>WrongSideScreen<!>() {
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Top))) {
        Text("wrong side")
    }
}

// Not satisfied: an explicit Scaffold argument replaces the knowledge-base default.
@HandlesWindowInsets(WindowInsetsType.SystemBars)
@Composable
fun <!KOTRAIL_WINDOW_INSETS_NOT_HANDLED!>ScaffoldOverrideScreen<!>() {
    Scaffold(contentWindowInsets = WindowInsets.ime) { _ -> Text("override") }
}

/* GENERATED_FIR_TAGS: additiveExpression, collectionLiteral, functionDeclaration, lambdaLiteral, stringLiteral */

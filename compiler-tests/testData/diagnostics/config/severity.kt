// KOTRAIL_CONFIG: rules.noPassThroughReturn=true, severity.noPassThroughReturn=warning, rules.preferExplicitBackingField=true, rules.compose.windowInsets=true, rules.compose.windowInsetsHandledTwice=true, severity.compose.windowInsetsHandledTwice=error
// A rule demoted to a warning reports under the `_WARNING` name; one promoted to an error
// reports under the `_ERROR` name. Untouched rules keep their bare names.
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

fun <!KOTRAIL_PASS_THROUGH_RETURN_WARNING!>identity<!>(x: Int) = x

@Composable
fun TopBarArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.statusBarsPadding()) { content() }
}

@Composable
fun DoublePadded() {
    <!KOTRAIL_WINDOW_INSETS_HANDLED_TWICE_ERROR!>TopBarArea(modifier = Modifier.safeDrawingPadding()) { Text("twice") }<!>
}

class Cases {
    private val _a = mutableListOf<String>()
    val <!KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD!>a<!>: List<String> get() = _a
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, functionalType, getter, lambdaLiteral, propertyDeclaration,
stringLiteral */

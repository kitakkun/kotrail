// KOTRAIL_CONFIG: rules.compose.nesting=on, rules.preferExplicitBackingField=on, rules.compose.windowInsets=on
// Kotrail diagnostics can be suppressed by name like built-in ones, per declaration or file.
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kitakkun.kotrail.compose.insets.HandlesWindowInsets
import com.kitakkun.kotrail.compose.insets.WindowInsetsType

@Suppress("KOTRAIL_COMPOSABLE_NESTING_TOO_DEEP")
@Composable
fun DeepButAllowed() {
    Column { Box { Column { Box { Column { Box { Text("seven") } } } } } }
}

@Composable
fun DeepAndReported() {
    Column { Box { Column { Box { Column { <!KOTRAIL_COMPOSABLE_NESTING_TOO_DEEP!>Box<!> { Text("seven") } } } } } }
}

class Suppressed {
    private val _a = mutableListOf<String>()
    @Suppress("KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD")
    val a: List<String> get() = _a
}

@Suppress("KOTRAIL_WINDOW_INSETS_NOT_HANDLED")
@HandlesWindowInsets(WindowInsetsType.SafeDrawing)
@Composable
fun MissingButAllowed() {
    Column(modifier = Modifier.statusBarsPadding()) { Text("missing") }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, getter, lambdaLiteral, propertyDeclaration, stringLiteral */

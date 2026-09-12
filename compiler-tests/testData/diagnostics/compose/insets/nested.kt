// KOTRAIL_CONFIG: rules.compose.windowInsets=true
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kitakkun.kotrail.compose.insets.HandlesWindowInsets
import com.kitakkun.kotrail.compose.insets.WindowInsetsType

// Composables that are not top-level functions: members of classes and objects, and local
// functions. Within a module they are analyzed from source like any other callee.

class ChatComponents {
    @Composable
    fun Composer() {
        Column(modifier = Modifier.imePadding()) { Text("composer") }
    }

    companion object {
        @Composable
        fun Header() {
            Column(modifier = Modifier.statusBarsPadding()) { Text("header") }
        }
    }
}

object StatusArea {
    @Composable
    fun Bar() {
        Column(modifier = Modifier.statusBarsPadding()) { Text("bar") }
    }
}

// Satisfied through an instance member and a companion member.
@HandlesWindowInsets(WindowInsetsType.Ime)
@HandlesWindowInsets(WindowInsetsType.StatusBars)
@Composable
fun MemberScreen(components: ChatComponents) {
    Column {
        ChatComponents.Header()
        components.Composer()
    }
}

// Satisfied through an object member.
@HandlesWindowInsets(WindowInsetsType.StatusBars)
@Composable
fun ObjectScreen() {
    StatusArea.Bar()
}

// Satisfied through a local composable declared inside the function body.
@HandlesWindowInsets(WindowInsetsType.Ime)
@Composable
fun LocalScreen() {
    @Composable
    fun LocalComposer() {
        Column(modifier = Modifier.imePadding()) { Text("local") }
    }
    LocalComposer()
}

// Not satisfied: the member handles the IME, not the status bars.
@HandlesWindowInsets(WindowInsetsType.StatusBars)
@Composable
fun <!KOTRAIL_WINDOW_INSETS_NOT_HANDLED!>MemberMismatchScreen<!>(components: ChatComponents) {
    components.Composer()
}

/* GENERATED_FIR_TAGS: classDeclaration, companionObject, functionDeclaration, lambdaLiteral, localFunction,
objectDeclaration, stringLiteral */

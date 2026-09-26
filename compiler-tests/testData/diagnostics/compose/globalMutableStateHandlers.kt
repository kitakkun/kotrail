// KOTRAIL_CONFIG: rules.compose.noGlobalMutableState=on, rules.compose.noGlobalMutableState.handlerWrites=false
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

var isDarkTheme = false

object Session {
    var count = 0
}

@Composable
fun Action(onClick: () -> Unit, content: @Composable () -> Unit) {
    content()
}

class Receiver {
    val values = HashMap<String, Boolean>()
}

var Receiver.selected: Boolean
    get() = values["selected"] == true
    set(value) { values["selected"] = value }

fun configure(block: Receiver.() -> Unit) = Receiver().block()

// Not reported with handlerWrites off: only assignments during composition count.
@Composable
fun Toggle() {
    Action(onClick = { isDarkTheme = !isDarkTheme }) {
        Text("toggle")
    }
    LaunchedEffect(Unit) {
        Session.count += 1
    }
    // Not reported either way: an extension var's setter writes into its receiver.
    configure { selected = true }
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, functionDeclaration, functionalType, integerLiteral,
lambdaLiteral, objectDeclaration, propertyDeclaration, stringLiteral */

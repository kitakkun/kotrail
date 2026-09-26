// KOTRAIL_CONFIG: rules.compose.noGlobalMutableState=on, rules.compose.noGlobalMutableState.handlerWrites=true
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

// Reported with handlerWrites: an assignment from a handler or an effect too; the read in the handler is still fine.
@Composable
fun Toggle() {
    Action(onClick = { <!KOTRAIL_GLOBAL_VAR_WRITTEN_IN_COMPOSABLE!>isDarkTheme = !isDarkTheme<!> }) {
        Text("toggle")
    }
    LaunchedEffect(Unit) {
        <!KOTRAIL_GLOBAL_VAR_WRITTEN_IN_COMPOSABLE!>Session.count<!> += 1
    }
    // Not reported even with handlerWrites: an extension var's setter writes into its receiver.
    configure { selected = true }
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, functionDeclaration, functionalType, integerLiteral,
lambdaLiteral, objectDeclaration, propertyDeclaration, stringLiteral */

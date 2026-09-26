// KOTRAIL_CONFIG: rules.compose.noGlobalMutableState=on
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class User(val name: String)

var isDarkTheme = false
val appName = "Kotrail"
const val VERSION = 3
lateinit var appContext: String
var themeState by mutableStateOf(false)
var counterState: MutableState<Int> = mutableStateOf(0)
var frames = 0
var lastQuery = ""

object Session {
    var user: User? = null
    val id = "session"
    var current by mutableStateOf<User?>(null)
}

class Settings {
    companion object {
        var compact = false
    }
}

class Holder {
    var value = 0
}

@Composable
fun Action(onClick: () -> Unit, content: @Composable () -> Unit) {
    content()
}

inline fun <T> compute(block: () -> T): T = block()

// Reported: a plain var, top-level or in an object, read during composition.
@Composable
fun Header(holder: Holder) {
    val title = if (<!KOTRAIL_GLOBAL_VAR_READ_IN_COMPOSITION!>isDarkTheme<!>) "dark" else "light"
    Text(title)
    Text(<!KOTRAIL_GLOBAL_VAR_READ_IN_COMPOSITION!>Session.user<!>?.name ?: "guest")
    Text(if (<!KOTRAIL_GLOBAL_VAR_READ_IN_COMPOSITION!>Settings.compact<!>) "compact" else "wide")
    // Reported once per var and function: the second read of isDarkTheme is not reported again.
    Text(isDarkTheme.toString())

    // Reported: reads inside what runs during composition, an inline lambda, a remember, a content slot.
    val label = compute { <!KOTRAIL_GLOBAL_VAR_READ_IN_COMPOSITION!>frames<!>.toString() }
    Text(label)
    val query = remember { <!KOTRAIL_GLOBAL_VAR_READ_IN_COMPOSITION!>lastQuery<!>.trim() }
    Text(query)

    // Not reported: a val, a const, a lateinit var, a delegated var, a var of State type, an instance var.
    Text(appName + VERSION + appContext + themeState + counterState.value + Session.id + Session.current?.name + holder.value)
}

// Reported: an assignment during composition, including through += and ++.
@Composable
fun Counter() {
    <!KOTRAIL_GLOBAL_VAR_WRITTEN_IN_COMPOSABLE!>frames<!>++
    <!KOTRAIL_GLOBAL_VAR_WRITTEN_IN_COMPOSABLE!>Session.user = null<!>
    Text("frames")
}

// Not reported by default: reads and writes in a handler or an effect see the value when they run;
// writes there are reported with handlerWrites: true (see globalMutableStateHandlers.kt).
@Composable
fun Toggle() {
    Action(onClick = { isDarkTheme = !isDarkTheme }) {
        Text(if (<!KOTRAIL_GLOBAL_VAR_READ_IN_COMPOSITION!>isDarkTheme<!>) "on" else "off")
    }
    LaunchedEffect(Unit) {
        Session.user = User("effect")
        frames = 0
    }
}

// Not reported: not a composable.
fun reset() {
    isDarkTheme = false
    Session.user = null
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, classDeclaration, companionObject, const, elvisExpression,
functionDeclaration, functionalType, ifExpression, incrementDecrementExpression, inline, integerLiteral, lambdaLiteral,
lateinit, localProperty, nullableType, objectDeclaration, primaryConstructor, propertyDeclaration, propertyDelegate,
safeCall, setter, stringLiteral, typeParameter */

// KOTRAIL_CONFIG: rules.compose.unwrittenState=on
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue

@Composable
fun Button(onClick: () -> Unit, content: @Composable () -> Unit) {
    content()
}

// Reported: a MutableState whose value is only read; remember { "Home" } says the same.
@Composable
fun NeverWritten() {
    val <!KOTRAIL_STATE_NEVER_WRITTEN!>title<!> = remember { mutableStateOf("Home") }
    Text(title.value)
}

// Reported: a delegated val cannot be assigned, so its MutableState is pointless.
@Composable
fun NeverWrittenDelegatedVal() {
    val <!KOTRAIL_STATE_NEVER_WRITTEN!>title<!> by rememberSaveable { mutableStateOf("Home") }
    Text(title)
}

// Not reported: a delegated var nothing assigns is a plain never-reassigned var, which preferVal reports.
@Composable
fun NeverWrittenDelegatedVar() {
    var title by remember { mutableStateOf("Home") }
    Text(title)
}

// Reported: assigned in a handler, never read; every click recomposes for a value nobody shows.
@Composable
fun NeverRead() {
    var <!KOTRAIL_STATE_NEVER_READ!>clicks<!> by remember { mutableStateOf(0) }
    Button(onClick = { clicks++ }) { Text("tap") }
}

// Reported: the same through .value.
@Composable
fun NeverReadValue() {
    val <!KOTRAIL_STATE_NEVER_READ!>draft<!> = rememberSaveable { mutableStateOf("") }
    Button(onClick = { draft.value = "sent" }) { Text("send") }
}

// Not reported: read in the body and written in the handler.
@Composable
fun ReadAndWritten() {
    var count by remember { mutableStateOf(0) }
    Column {
        Text("count = $count")
        Button(onClick = { count += 1 }) { Text("more") }
    }
}

// Not reported: written and read through .value.
@Composable
fun ReadAndWrittenValue() {
    val count = remember { mutableStateOf(0) }
    Text("count = ${count.value}")
    Button(onClick = { count.value = count.value + 1 }) { Text("more") }
}

// Not reported: the MutableState is passed on, so another composable may write it.
@Composable
fun Hoisted() {
    val count = remember { mutableStateOf(0) }
    Text("count = ${count.value}")
    Controls(count)
}

@Composable
fun Controls(count: MutableState<Int>) {
    count.value++
}

// Not reported: the MutableState is returned.
@Composable
fun rememberCounter(): MutableState<Int> {
    val count = remember { mutableStateOf(0) }
    Text("count = ${count.value}")
    return count
}

// Not reported: a state that came in as a parameter is written by its owner.
@Composable
fun DelegatedParameter(armed: MutableState<Boolean>) {
    val isArmed by armed
    Text("armed = $isArmed")
}

// Not reported: the same through .value.
@Composable
fun ParameterValue(armed: MutableState<Boolean>) {
    val state = armed
    Text("armed = ${state.value}")
}

// Not reported: remembering a state made elsewhere does not make it this function's own.
@Composable
fun RememberedParameter(armed: MutableState<Boolean>) {
    val isArmed by remember { armed }
    Text("armed = $isArmed")
}

class Trigger(private val armed: MutableState<Boolean>) {
    // Not reported: a state held by the class, made by whoever constructed it.
    @Composable
    fun Content() {
        val isArmed by armed
        Text("armed = $isArmed")
    }
}

// Not reported: a remembered plain value, not a state.
@Composable
fun PlainRemember() {
    val label = remember { "x" }
    Text(label)
}

// Not reported: outside a composable.
fun notComposable(): String {
    val title = mutableStateOf("Home")
    return title.value
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, functionDeclaration, functionalType, incrementDecrementExpression,
integerLiteral, lambdaLiteral, localProperty, nullableType, propertyDeclaration, propertyDelegate, setter, stringLiteral */

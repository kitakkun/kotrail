// KOTRAIL_CONFIG: rules.compose.stateDelegation=on
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue

// Reported: a MutableState used only through .value (reads and writes). Suggests `var ... by`.
@Composable
fun Counter() {
    val <!KOTRAIL_PREFER_STATE_DELEGATION!>count<!> = remember { mutableStateOf(0) }
    Column {
        Text("count = ${count.value}")
        count.value = count.value + 1
    }
}

// Reported: a read-only State from derivedStateOf. Suggests `val ... by`.
@Composable
fun Derived(items: List<String>) {
    val <!KOTRAIL_PREFER_STATE_DELEGATION!>size<!> = remember { derivedStateOf { items.size } }
    Text("size = ${size.value}")
}

// Reported: rememberSaveable counts too, and usages inside nested lambdas are found.
@Composable
fun Saved() {
    val <!KOTRAIL_PREFER_STATE_DELEGATION!>query<!> = rememberSaveable { mutableStateOf("") }
    LazyColumn {
        items(3) { Text(query.value) }
    }
}

// Already delegated: nothing to report.
@Composable
fun Delegated() {
    var count by remember { mutableStateOf(0) }
    Text("count = $count")
    count++
}

// Not reported: the State object escapes as an argument, so delegation would change the code.
@Composable
fun Hoisted() {
    val count = remember { mutableStateOf(0) }
    Text("count = ${count.value}")
    CounterControls(count)
}

@Composable
fun CounterControls(count: MutableState<Int>) {
    count.value++
}

// Not reported: the State object is used as an effect key.
@Composable
fun AsKey() {
    val loaded = remember { mutableStateOf(false) }
    LaunchedEffect(loaded) { }
    Text("${loaded.value}")
}

// Not reported: an explicit State-typed local that escapes through a return value.
@Composable
fun returnedState(): State<Int> {
    val state: State<Int> = remember { mutableStateOf(0) }
    Text("${state.value}")
    return state
}

// Not reported: not a State.
@Composable
fun PlainRemember() {
    val label = remember { "x" }
    Text(label)
}

// Not reported: outside a composable.
fun NotComposable(): Int {
    val count = mutableStateOf(0)
    return count.value
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, functionDeclaration, incrementDecrementExpression, integerLiteral,
lambdaLiteral, localProperty, nullableType, propertyDeclaration, propertyDelegate, setter, stringLiteral */

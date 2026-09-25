// KOTRAIL_CONFIG: rules.compose.rememberKeys=on
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Observable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

class User(val name: String)

fun format(amount: Long): String = amount.toString()
suspend fun load(id: Long) {}
fun track(name: String) {}

// Reported: the lambda reads parameters that are not among the keys.
@Composable
fun Price(amount: Long, id: Long) {
    val text = <!KOTRAIL_EFFECT_KEY_MISSING!>remember { format(amount) }<!>
    <!KOTRAIL_EFFECT_KEY_MISSING!>LaunchedEffect(Unit) { load(id) }<!>
    Text(text)
}

// Not reported: keyed on what it reads.
@Composable
fun KeyedPrice(amount: Long, id: Long) {
    val text = remember(amount) { format(amount) }
    LaunchedEffect(id) { load(id) }
    Text(text)
}

// Reported: a delegated local (state) read in a keyless effect; not reported inside derivedStateOf,
// which observes on its own.
@Composable
fun Counter(onClick: () -> Unit) {
    var count by remember { mutableStateOf(0) }
    <!KOTRAIL_EFFECT_KEY_MISSING!>LaunchedEffect(Unit) { track("$count") }<!>
    val high by remember { derivedStateOf { count > 3 } }
    Text(if (high) "high" else "low")
    count++
    onClick()
}

// Not reported: rememberUpdatedState is the sanctioned way to read the latest value from a keyless
// effect; a scope from rememberCoroutineScope is stable; snapshotFlow observes what it reads.
@Composable
fun Latest(onClick: () -> Unit, state: Observable<Int>) {
    val current by rememberUpdatedState(onClick)
    val scope = rememberCoroutineScope()
    val value by state.collectAsState()
    LaunchedEffect(Unit) {
        current()
        scope.launch { }
        snapshotFlow { value }.collect { track("$it") }
    }
    Text("$value")
}

// Reported: a local computed from a parameter is as stale as the parameter; the message names the local.
@Composable
fun Label(user: User) {
    val label = user.name
    val upper = <!KOTRAIL_EFFECT_KEY_MISSING!>remember { label.uppercase() }<!>
    Text(upper)
}

// Not reported: a local computed from a parameter, but keyed on that local.
@Composable
fun KeyedLabel(user: User) {
    val label = user.name
    val upper = remember(label) { label.uppercase() }
    Text(upper)
}

// Not reported: not a composable (the Compose compiler is not applied here, so the call compiles).
fun plain(amount: Long): String = remember { format(amount) }

/* GENERATED_FIR_TAGS: assignment, classDeclaration, comparisonExpression, functionDeclaration, functionalType,
ifExpression, incrementDecrementExpression, integerLiteral, lambdaLiteral, localProperty, nullableType,
primaryConstructor, propertyDeclaration, propertyDelegate, setter, stringLiteral, suspend */

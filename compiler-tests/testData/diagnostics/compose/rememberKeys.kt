// KOTRAIL_CONFIG: rules.compose.rememberKeys=on
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Observable
import androidx.compose.runtime.awaitCancellation
import androidx.compose.runtime.collect
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
class Request(val url: String)
class Transaction(val request: Request)

class Session(val iconBase64: String?)
class Flags(val interactiveOnly: Boolean)
class SplitState(val fraction: Float)

fun format(amount: Long): String = amount.toString()
fun parse(url: String): String = url
suspend fun load(id: Long) {}
suspend fun pause() {}
fun track(name: String) {}
fun step(speed: Int) {}
fun filterBy(predicate: (String) -> Boolean): Int = if (predicate("")) 1 else 0

// Reported: a remember computes from a parameter that is not among its keys; the fix is the key.
@Composable
fun Price(amount: Long) {
    val text = <!KOTRAIL_EFFECT_KEY_MISSING!>remember { format(amount) }<!>
    Text(text)
}

// Not reported: keyed on what it reads.
@Composable
fun KeyedPrice(amount: Long) {
    val text = remember(amount) { format(amount) }
    Text(text)
}

// Not reported: a one-shot effect that uses this composition's value once is what such effects are
// for; only a long-lived body freezes a data value (see Ticker and Feed below).
@Composable
fun Loader(id: Long) {
    LaunchedEffect(Unit) { load(id) }
}

// Reported: a callback captured by an effect; the fix is rememberUpdatedState, since restarting the
// collection because a lambda changed identity is the classic bug.
@Composable
fun Feed(events: Observable<String>, onEvent: (String) -> Unit) {
    <!KOTRAIL_EFFECT_KEY_MISSING!>LaunchedEffect(Unit) { events.collect { onEvent(it) } }<!>
}

// Reported: a data value read for as long as the collection runs; the message offers both fixes.
@Composable
fun Tagged(events: Observable<String>, tag: String) {
    <!KOTRAIL_EFFECT_KEY_MISSING!>LaunchedEffect(Unit) { events.collect { track(tag + it) } }<!>
}

// Reported: a loop is a long-lived body too.
@Composable
fun Ticker(speed: Int) {
    <!KOTRAIL_EFFECT_KEY_MISSING!>LaunchedEffect(Unit) { while (true) { step(speed) } }<!>
}

// Reported: onDispose runs at the very end with whatever it captured.
@Composable
fun Closing(onClose: () -> Unit) {
    <!KOTRAIL_EFFECT_KEY_MISSING!>DisposableEffect(Unit) { onDispose { onClose() } }<!>
}

// Reported: a body that waits for cancellation keeps its reads for the effect's whole life.
@Composable
fun Waiting(tag: String) {
    <!KOTRAIL_EFFECT_KEY_MISSING!>LaunchedEffect(Unit) {
        track(tag)
        awaitCancellation()
    }<!>
}

// Not reported: a State is always current when read, and writing to one is not a read at all; a
// State read inside derivedStateOf observes on its own.
@Composable
fun Counter(onClick: () -> Unit) {
    var count by remember { mutableStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        track("$count")
        visible = true
    }
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

// Not reported: the key spells the property path the lambda reads.
@Composable
fun Url(tx: Transaction) {
    val parsed = remember(tx.request.url) { parse(tx.request.url) }
    Text(parsed)
}

// Not reported: the key spells the property path through a safe call, and a path read inside a
// nested lambda of the body is covered all the same.
@Composable
fun SafeIcon(session: Session?) {
    val icon = remember(session?.iconBase64) { session?.iconBase64?.let { parse(it) } }
    Text(icon ?: "")
}

@Composable
fun Filtered(flags: Flags) {
    val count = remember(flags.interactiveOnly) { filterBy { node -> flags.interactiveOnly && node.isNotEmpty() } }
    Text("$count")
}

// Reported: the same nested read with no key.
@Composable
fun Unfiltered(flags: Flags) {
    val count = <!KOTRAIL_EFFECT_KEY_MISSING!>remember { filterBy { node -> flags.interactiveOnly && node.isNotEmpty() } }<!>
    Text("$count")
}

// Not reported: the initial value handed to mutableStateOf, or to a constructor inside remember, is
// meant to be taken once.
@Composable
fun Seeded(initial: Int, initialFraction: Float) {
    var value by remember { mutableStateOf(initial) }
    val split = remember { SplitState(initialFraction) }
    Text("$value ${split.fraction}")
    value++
}

// Not reported: a callback called before the effect's first suspension point sees this
// composition's value once, which is what a one-shot effect is for.
@Composable
fun Starting(onStart: () -> Unit) {
    LaunchedEffect(Unit) {
        onStart()
        pause()
    }
}

// Reported: the same callback read after the suspension point, when the composition may have moved on.
@Composable
fun Resuming(onStart: () -> Unit) {
    <!KOTRAIL_EFFECT_KEY_MISSING!>LaunchedEffect(Unit) {
        onStart()
        pause()
        onStart()
    }<!>
}

// Reported: a local computed from a parameter is as stale as the parameter; the message names the local.
@Composable
fun Label(user: User) {
    val label = user.name
    val upper = <!KOTRAIL_EFFECT_KEY_MISSING!>remember { label.uppercase() }<!>
    Text(upper)
}

// Not reported: a local computed from a parameter, keyed on that local; and a local derived only
// from a keyed value, or from another remember, is covered by that key.
@Composable
fun KeyedLabel(user: User) {
    val label = user.name
    val upper = remember(label) { label.uppercase() }
    val shout = upper + "!"
    val loud = remember { shout.length }
    Text(upper + loud)
}

// Not reported: not a composable (the Compose compiler is not applied here, so the call compiles).
fun plain(amount: Long): String = remember { format(amount) }

/* GENERATED_FIR_TAGS: additiveExpression, assignment, classDeclaration, comparisonExpression, functionDeclaration,
functionalType, ifExpression, incrementDecrementExpression, integerLiteral, lambdaLiteral, localProperty, nullableType,
primaryConstructor, propertyDeclaration, propertyDelegate, setter, stringLiteral, suspend, whileLoop */

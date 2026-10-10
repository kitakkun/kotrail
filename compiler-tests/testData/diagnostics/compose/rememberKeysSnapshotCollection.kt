// KOTRAIL_CONFIG: rules.compose.rememberKeys=on
// RENDER_DIAGNOSTICS_FULL_TEXT
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList

suspend fun send(count: Int) {}

// Reported, asking for derivedStateOf or snapshotFlow rather than a key: the list keeps its
// identity while its contents change, so a key on it would never restart the calculation.
@Composable
fun Visible(items: SnapshotStateList<String>, query: String) {
    val visible = <!KOTRAIL_EFFECT_KEY_MISSING!>remember(query) { items.filter { it.contains(query) } }<!>
    Text(visible.joinToString())
}

// The same for a map read in a long-lived effect body.
@Composable
fun Counted(counts: SnapshotStateMap<String, Int>) {
    <!KOTRAIL_EFFECT_KEY_MISSING!>LaunchedEffect(Unit) { while (true) { send(counts.size) } }<!>
}

// The same for a local made by toMutableStateList, declared as a plain List.
@Composable
fun Copied(names: List<String>) {
    val editable: List<String> = names.toMutableStateList()
    val first = <!KOTRAIL_EFFECT_KEY_MISSING!>remember { editable.firstOrNull() }<!>
    Text(first ?: "")
}

// Reported with "add it to the keys": a plain list changes by identity when it changes.
@Composable
fun Plain(items: List<String>) {
    val first = <!KOTRAIL_EFFECT_KEY_MISSING!>remember { items.firstOrNull() }<!>
    Text(first ?: "")
}

// Reported with "add it to the keys": the lambda keeps the list itself, not what it holds.
@Composable
fun Kept(items: SnapshotStateList<String>) {
    val kept = <!KOTRAIL_EFFECT_KEY_MISSING!>remember { items }<!>
    Text(kept.joinToString())
}

// Not reported: a key on a copy of the list changes with its contents.
@Composable
fun Snapshotted(items: SnapshotStateList<String>, query: String) {
    val visible = remember(items.toList(), query) { items.filter { it.contains(query) } }
    Text(visible.joinToString())
}

// Not reported: derivedStateOf observes the contents.
@Composable
fun Observed(items: SnapshotStateList<String>) {
    val count by remember { derivedStateOf { items.size } }
    Text(count.toString())
}

/* GENERATED_FIR_TAGS: elvisExpression, functionDeclaration, lambdaLiteral, localProperty, nullableType,
propertyDeclaration, propertyDelegate, stringLiteral, suspend, whileLoop */

// KOTRAIL_CONFIG: rules.compose.rememberKeys=on, rules.compose.liveValueRemembered=on
// RENDER_DIAGNOSTICS_FULL_TEXT
// With both rules on, a value read live in a remember is liveValueRemembered's finding alone:
// rememberKeys leaves it out of that remember's missing keys and still reports the others.
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap

suspend fun send(count: Int) {}

// One finding: the snapshot list read in the calculation is reported as live, not as a missing key.
@Composable
fun Visible(items: SnapshotStateList<String>, query: String) {
    val visible = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember(query) { items.filter { it.contains(query) } }<!>
    Text(visible.joinToString())
}

// The same for a delegated state read with no key at all.
@Composable
fun Title() {
    var query by remember { mutableStateOf("") }
    val title = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember { query.uppercase() }<!>
    Text(title)
    query = "api"
}

// Two findings: the live list is liveValueRemembered's, the plain suffix is still a missing key.
@Composable
fun Labeled(items: SnapshotStateList<String>, suffix: String) {
    val labels = <!KOTRAIL_EFFECT_KEY_MISSING, KOTRAIL_LIVE_VALUE_REMEMBERED!>remember { items.map { it + suffix } }<!>
    Text(labels.joinToString())
}

// Still reported by rememberKeys: liveValueRemembered checks remember, not an effect.
@Composable
fun Counted(counts: SnapshotStateMap<String, Int>) {
    <!KOTRAIL_EFFECT_KEY_MISSING!>LaunchedEffect(Unit) { while (true) { send(counts.size) } }<!>
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, functionDeclaration, lambdaLiteral, localProperty, nullableType,
propertyDeclaration, propertyDelegate, setter, stringLiteral, suspend, whileLoop */

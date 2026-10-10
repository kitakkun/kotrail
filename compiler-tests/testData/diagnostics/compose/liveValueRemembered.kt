// KOTRAIL_CONFIG: rules.compose.liveValueRemembered=on
// RENDER_DIAGNOSTICS_FULL_TEXT
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Observable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap

class Tx(val url: String)

fun redact(tx: Tx): Tx = Tx(tx.url.substringBefore('?'))

class TrafficModel {
    val items = mutableStateListOf<Tx>()
    var filter by mutableStateOf("")
}

object Store {
    val items = mutableStateListOf<Tx>()
}

// Reported: the same SnapshotStateList is the key on every composition, so the filtered copy keeps
// what the list held the first time, whatever is added to it in place.
@Composable
fun LiveTrafficTab(transactions: SnapshotStateList<Tx>) {
    var query by remember { mutableStateOf("") }
    val visible = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember(transactions, query) { transactions.filter { it.url.contains(query) } }<!>
    Text("${visible.size}")
    query = "api"
}

// Reported: the State object is the key, and it stays the same object when its value changes.
@Composable
fun QueryLength(queryState: State<String>) {
    val length = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember(queryState) { queryState.value.length }<!>
    Text("$length")
}

// Reported: a delegated state read with no key at all.
@Composable
fun QueryTitle() {
    var query by remember { mutableStateOf("") }
    val title = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember { query.uppercase() }<!>
    Text(title)
    query = "api"
}

// Reported: a state collected from a flow is as live as one made here.
@Composable
fun CollectedTitle(source: Observable<String>) {
    val text by source.collectAsState()
    val title = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember(source) { text.uppercase() }<!>
    Text(title)
}

// Reported: a model's snapshot list and delegated state, both read through the model, which is the key.
@Composable
fun ModelTraffic(model: TrafficModel) {
    val visible = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember(model) { model.items.filter { it.url.contains(model.filter) } }<!>
    Text("${visible.size}")
}

// Reported: a for loop, an index, a map's keys, and a list read through an object all read the contents.
@Composable
fun Contents(transactions: SnapshotStateList<Tx>, byUrl: SnapshotStateMap<String, Tx>) {
    val urls = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember(transactions) {
        val result = ArrayList<String>()
        for (tx in transactions) result.add(tx.url)
        result
    }<!>
    val first = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember(transactions) { transactions[0] }<!>
    val keys = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember(byUrl) { byUrl.keys.sorted() }<!>
    val stored = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember { Store.items.size }<!>
    Text("${urls.size} ${first.url} ${keys.size} $stored")
}

// Reported: rememberSaveable keys its value the same way.
@Composable
fun SavedCount(transactions: SnapshotStateList<Tx>) {
    val count = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>rememberSaveable(transactions) { transactions.size }<!>
    Text("$count")
}

// Not reported: the value read is the key, and changes with it.
@Composable
fun QueryKeyed() {
    var query by remember { mutableStateOf("") }
    val length = remember(query) { query.length }
    Text("$length")
    query = "api"
}

// Not reported: a key that reads the State's value changes with it.
@Composable
fun QueryValueKeyed(queryState: State<String>) {
    val length = remember(queryState.value) { queryState.value.length }
    Text("$length")
}

// Reported: a key that reads only the size does not change when an element is replaced in place
// (`transactions[i] = updated`), and the sorted copy keeps the old element.
@Composable
fun SizeKeyed(transactions: SnapshotStateList<Tx>) {
    val sorted = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember(transactions.size) { transactions.sortedBy { it.url } }<!>
    Text("${sorted.size}")
}

// Not reported: derivedStateOf tracks what it reads, and follows the contents. The key is still
// the parameter, for a caller that hands a different list.
@Composable
fun DerivedTraffic(transactions: SnapshotStateList<Tx>) {
    var query by remember { mutableStateOf("") }
    val visible by remember(transactions) { derivedStateOf { transactions.filter { it.url.contains(query) } } }
    val local = remember { mutableStateListOf<Tx>() }
    val count by remember { derivedStateOf { local.filter { it.url.isNotEmpty() }.size } }
    Text("${visible.size} $count")
    query = "api"
}

// Not reported: a lambda made in the calculation reads the list when it is called, and sees it then.
@Composable
fun CountButton(transactions: SnapshotStateList<Tx>) {
    val onClick = remember(transactions) { { println(transactions.size) } }
    onClick()
}

// Not reported: the initial value of a new state is taken once on purpose, and so is what a
// constructor is handed; a list created in the calculation is new, not stale.
@Composable
fun Draft(queryState: State<String>, transactions: SnapshotStateList<Tx>) {
    var draft by remember { mutableStateOf(queryState.value) }
    val holder = remember { Pair(transactions, queryState) }
    val fresh = remember { mutableStateListOf<Tx>().also { it.add(Tx("a")) } }
    Text(draft + holder.first.size + fresh.size)
    draft = "x"
}

// Not reported here: a plain List cannot be told from a live one inside this function. TrafficTab
// keys a filtered copy on the list's identity, which its identityKeyedParameters fact records
// (["transactions"]) for the callers below.
@Composable
fun TrafficTab(transactions: List<Tx>) {
    var query by remember { mutableStateOf("") }
    val visible = remember(transactions, query) { transactions.filter { it.url.contains(query) } }
    Text("${visible.size}")
    query = "api"
}

// Hands its parameter on to TrafficTab: recorded as keyed too, so that its callers are checked.
@Composable
fun TrafficPane(transactions: List<Tx>) {
    TrafficTab(transactions)
}

// Keys its copy on a local that is the parameter: recorded as keyed all the same.
@Composable
fun AliasedTab(transactions: List<Tx>?) {
    val list = transactions!!
    val count = remember(list) { list.count { it.url.isNotEmpty() } }
    Text("$count")
}

// Keeps the list itself in what it remembers, not a copy: nothing recorded.
@Composable
fun KeptTab(transactions: List<Tx>) {
    val holder = remember(transactions) { Pair(transactions, "all") }
    Text(holder.second)
}

// Reported: one SnapshotStateList for the whole session, handed to a parameter TrafficTab keys a copy on.
@Composable
fun TrafficScreen() {
    TrafficTab(<!KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE!>remember { mutableStateListOf<Tx>() }<!>)
}

// Reported: the same list through a local, and through the wrapper that hands it on; the message
// for the wrapper names TrafficTab, where the remember is.
@Composable
fun SessionScreen() {
    val transactions = remember { mutableStateListOf<Tx>() }
    TrafficTab(<!KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE!>transactions<!>)
    TrafficPane(<!KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE!>transactions<!>)
    AliasedTab(<!KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE!>transactions<!>)
}

// Reported: a local declared as a plain List still holds the live one.
@Composable
fun DeclaredListScreen() {
    val transactions: List<Tx> = remember { mutableStateListOf<Tx>() }
    TrafficTab(<!KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE!>transactions<!>)
}

// Not reported: a snapshot is a new list whenever the contents change, a plain list is the
// caller's to replace, and KeptTab keeps the live list itself.
@Composable
fun SnapshotScreen(items: List<Tx>) {
    val transactions = remember { mutableStateListOf<Tx>() }
    TrafficTab(transactions.toList())
    TrafficTab(items)
    KeptTab(transactions)
}

// Not reported: LiveTrafficTab declares its parameter as a SnapshotStateList, and is reported itself.
@Composable
fun LiveScreen() {
    LiveTrafficTab(remember { mutableStateListOf<Tx>() })
}

// Reported: a branch of the argument hands the live list on (when `redact` is false, or `maybe`
// is there), and the message names that branch. So does a local initialized with such a branch.
@Composable
fun BranchScreen(redact: Boolean, maybe: SnapshotStateList<Tx>?) {
    val transactions = remember { mutableStateListOf<Tx>() }
    TrafficTab(transactions = <!KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE!>if (redact) transactions.map(::redact) else transactions<!>)
    TrafficTab(<!KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE!>when { redact -> emptyList(); else -> transactions }<!>)
    TrafficTab(<!KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE!>maybe ?: emptyList()<!>)
    TrafficTab(<!KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE!>try { transactions } catch (e: Exception) { emptyList() }<!>)
    val shown = if (redact) emptyList() else transactions
    TrafficTab(<!KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE!>shown<!>)
}

// Not reported: every branch is a new list.
@Composable
fun SnapshotBranchScreen(redact: Boolean) {
    val transactions = remember { mutableStateListOf<Tx>() }
    TrafficTab(if (redact) transactions.map(::redact) else transactions.toList())
}

class RunRecord(val id: String)

class RunStore {
    private val runs = mutableStateListOf<RunRecord>()
    val history: List<RunRecord> get() = runs
}

@Composable
fun RunsTab(runs: List<RunRecord>) {
    val sorted = remember(runs) { runs.sortedBy { it.id } }
    Text("${sorted.size}")
}

// Reported: the getter hands out the store's live list itself, one hop away.
@Composable
fun RunsScreen(store: RunStore) {
    RunsTab(<!KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE!>store.history<!>)
}

class Pager(val items: List<Tx>)

fun createPager(items: List<Tx>): Pager = Pager(items)

// Reported: the receiver of a call, a destructuring and `in` read the contents. Not reported: a
// list handed whole to a call is kept, not read, whether the call is a factory or a constructor.
@Composable
fun PagerTab(items: SnapshotStateList<Tx>, tx: Tx) {
    val made = remember(items) { createPager(items) }
    val built = remember(items) { Pager(items) }
    val filtered = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember(items) { items.filter { it.url.isNotEmpty() } }<!>
    val pair = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember(items) {
        val (first, second) = items
        first.url + second.url
    }<!>
    val contained = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember(items) { tx in items }<!>
    Text("${made.items.size} ${built.items.size} ${filtered.size} $pair $contained")
}

// Hands its list whole to a factory that keeps it: nothing recorded, so its callers stay quiet.
@Composable
fun PagerHost(items: List<Tx>) {
    val pager = remember(items) { createPager(items) }
    Text("${pager.items.size}")
}

@Composable
fun PagerScreen() {
    val items = remember { mutableStateListOf<Tx>() }
    PagerHost(items)
}

// Not reported: a keyless remember of one bare read of a State takes the initial value once, on
// purpose. Reported: a result derived from it, or from a snapshot list, goes stale.
@Composable
fun InitialValues(state: State<String>, list: SnapshotStateList<Tx>) {
    var count by remember { mutableStateOf(0) }
    val initial = remember { state.value }
    val initialCount = remember { count }
    val savedCount = rememberSaveable { count }
    val length = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember { state.value.length }<!>
    val copy = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember { list.toList() }<!>
    val visible = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember { list.filter { it.url.isNotEmpty() } }<!>
    Text("$initial $initialCount $savedCount $length ${copy.size} ${visible.size}")
    count = 1
}

// Reported: a key that reads only the size or emptiness does not cover a read of the elements.
// Not reported: a calculation that reads no more than the key does, and a key that reads the
// elements themselves.
@Composable
fun SizeKeys(list: SnapshotStateList<Tx>) {
    val filtered = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember(list.size) { list.filter { it.url.isNotEmpty() } }<!>
    val urls = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember(list.isEmpty()) { list.map { it.url } }<!>
    val counted = <!KOTRAIL_LIVE_VALUE_REMEMBERED!>remember(list.count()) { list.first() }<!>
    val doubled = remember(list.size) { list.size * 2 }
    val last = remember(list.lastIndex) { list.indices.last }
    val whole = remember(list.toList()) { list.filter { it.url.isNotEmpty() } }
    Text("${filtered.size} ${urls.size} ${counted.url} $doubled $last ${whole.size}")
}

// Hands its parameter on to TrafficPane, which hands it on to TrafficTab: recorded with TrafficTab
// as the composable that holds the remember.
@Composable
fun TrafficRoot(transactions: List<Tx>) {
    TrafficPane(transactions)
}

// Reported: the message names TrafficTab, two wrappers down, as the composable to change.
@Composable
fun RootScreen() {
    val transactions = remember { mutableStateListOf<Tx>() }
    TrafficRoot(<!KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE!>transactions<!>)
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, checkNotNullCall, classDeclaration, forLoop, functionDeclaration,
integerLiteral, lambdaLiteral, localProperty, nullableType, objectDeclaration, primaryConstructor, propertyDeclaration,
propertyDelegate, setter, stringLiteral */

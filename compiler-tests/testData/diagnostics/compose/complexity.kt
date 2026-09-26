// KOTRAIL_CONFIG: rules.compose.complexity=on, rules.compose.complexity.maxScore=8
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Observable
import androidx.compose.runtime.collect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class Item(val name: String, val selected: Boolean)

@Composable
fun Column(content: @Composable () -> Unit) {
    content()
}

@Composable
fun Row(content: @Composable () -> Unit) {
    content()
}

suspend fun load(query: String): List<Item> = emptyList()
suspend fun observe(events: Observable<String>, onEvent: (String) -> Unit) {}

// Not reported: a list item with one branch scores 1.
@Composable
fun ItemRow(item: Item) {
    Text(if (item.selected) "[x] ${item.name}" else "[ ] ${item.name}")
}

// Not reported: a small screen. States 2, effect 2 + 1 key + 2 for writing a state = 5, one branch: 8, at the limit.
@Composable
fun Search(query: String) {
    var results by remember { mutableStateOf(emptyList<Item>()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        loading = true
        results = load(query)
    }
    if (loading) Text("loading") else Text(results.size.toString())
}

// Reported: the points pile up in the Column's items loop, which is the place to extract.
@Composable
fun <!KOTRAIL_COMPOSABLE_TOO_COMPLEX!>Overview<!>(items: List<Item>, filter: String?, events: Observable<String>) {
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(events) { events.collect { count++ } }
    <!KOTRAIL_COMPOSABLE_COMPLEXITY_HOTSPOT!>Column {
        items.forEach { item ->
            val label = filter ?: item.name
            if (item.selected && count > 0) {
                Text("$label!")
            } else if (item.name.isEmpty()) {
                Text("?")
            } else {
                Text(label)
            }
        }
    }<!>
    Text(count.toString())
}

// Reported: the points are spread, so the largest share is named instead of a block.
@Composable
fun <!KOTRAIL_COMPOSABLE_TOO_COMPLEX!>Dashboard<!>(a: Boolean, b: Boolean, c: Boolean, d: Boolean) {
    var x by remember { mutableStateOf(0) }
    var y by remember { mutableStateOf(0) }
    var z by remember { mutableStateOf(0) }
    val w = remember { mutableStateOf(0) }
    if (a) Text("a")
    if (b) Text("b")
    if (c) Text("c")
    if (d) Text("d")
    DisposableEffect(Unit) { onDispose { } }
    Text("$x $y $z ${w.value}")
}

/* GENERATED_FIR_TAGS: andExpression, assignment, classDeclaration, comparisonExpression, elvisExpression,
functionDeclaration, functionalType, ifExpression, incrementDecrementExpression, integerLiteral, lambdaLiteral,
localProperty, nullableType, primaryConstructor, propertyDeclaration, propertyDelegate, setter, stringLiteral, suspend */

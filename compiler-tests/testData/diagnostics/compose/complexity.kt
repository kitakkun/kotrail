// KOTRAIL_CONFIG: rules.compose.complexity=on, rules.compose.complexity.maxScore=5, rules.compose.complexity.hotspotMin=4
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

class Item(val name: String, val selected: Boolean)

object Labels {
    const val NONE = "-"
}

class Palette(val hover: Int, val idle: Int, val pressed: Int, val disabled: Int)

class Theme(val colors: Palette)

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
suspend fun send(action: String, id: String) {}

// Not reported: a list item with one branch scores 1.
@Composable
fun ItemRow(item: Item) {
    Text(if (item.selected) "[x] ${item.name}" else "[ ] ${item.name}")
}

// Not reported: a small screen. States 2, effect 1 + 1 for writing a state, one branch: 5, at the limit.
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

// Not reported: a fallback to a literal or a constant is a default, not a path the reader follows.
@Composable
fun Defaults(a: String?, b: String?, c: String?, d: String?, e: String?, f: String?) {
    Column {
        Text(a ?: "")
        Text(b ?: "")
        Text(c ?: Labels.NONE)
        Text(d ?: Labels.NONE)
        Text(e ?: "")
        Text(f ?: "")
    }
}

// Not reported: a lookup of constants is one branch, a boolean written as a value is none,
// and rememberUpdatedState holds a callback, not state.
@Composable
fun Styled(hovered: Boolean, selected: Boolean, enabled: Boolean, focused: Boolean, item: Item, onClick: () -> Unit) {
    val current = rememberUpdatedState(onClick)
    val label = when {
        hovered && selected -> "both"
        hovered -> "hovered"
        selected -> "selected"
        else -> "none"
    }
    val color = if (enabled && focused) Labels.NONE else ""
    val active = enabled && !item.selected && focused
    Text(if (active) label else color)
    current.value
}

// Not reported: a table of property references is a lookup like a table of constants. The `if` and
// the `when` score 1 each and the reference fallback nothing: 2 in all (per row it would be 7). The Row
// spans the whole body, so it would never be named anyway.
@Composable
fun Swatch(colors: Palette, theme: Theme, hovered: Boolean, pressed: Boolean, enabled: Boolean, custom: Int?) {
    Row {
        val background = if (hovered) colors.hover else colors.idle
        val border = when {
            !enabled -> theme.colors.disabled
            pressed && hovered -> theme.colors.pressed
            hovered -> theme.colors.hover
            else -> theme.colors.idle
        }
        val accent = custom ?: theme.colors.idle
        Text("$background $border $accent")
    }
}

// Standalone hotspot: the composable scores 4, within the limit, but the effect carries all 4 on its
// own (1 for the effect, 3 for the computed rows of its `when`), which reaches `hotspotMin` (4).
@Composable
fun Dispatcher(request: String, id: String, title: String, subtitle: String) {
    <!KOTRAIL_COMPOSABLE_HOTSPOT_STANDALONE!>LaunchedEffect(request) {
        when (request) {
            "open" -> send("open", id)
            "close" -> send("close", id)
            "reload" -> send("reload", id)
            else -> send("unknown", id)
        }
    }<!>
    Column {
        Text(title)
        Text(subtitle)
        Text(request)
        Text(id)
    }
}

// Reported: the points pile up in the Column, which is the place to extract. States 1, effect 1 + 1 for
// writing a state, and the Column 5 (the loop, the computed fallback, the `if` with its `&&`, the
// `else if`): 8.
@Composable
fun <!KOTRAIL_COMPOSABLE_TOO_COMPLEX!>Overview<!>(items: List<Item>, filter: String?, events: Observable<String>) {
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(events) { events.collect { count++ } }
    <!KOTRAIL_COMPOSABLE_COMPLEXITY_HOTSPOT!>Column {
        items.forEach { item ->
            val label = filter ?: item.name.trim()
            if (item.selected && count > 0) {
                Text("$label!")
            } else if (item.name.isEmpty()) {
                Text("?")
            } else {
                Text(label)
            }
        }
        Text(count.toString())
    }<!>
}

// Reported: the points are spread, so the kinds that dominate are named instead of a block. States 4,
// effect 1, four `if`s in the Column (4, under the 5 a hotspot needs): 9.
@Composable
fun <!KOTRAIL_COMPOSABLE_TOO_COMPLEX!>Dashboard<!>(a: Boolean, b: Boolean, c: Boolean, d: Boolean) {
    var x by remember { mutableStateOf(0) }
    var y by remember { mutableStateOf(0) }
    var z by remember { mutableStateOf(0) }
    val w = remember { mutableStateOf(0) }
    DisposableEffect(Unit) { onDispose { } }
    Column {
        if (a) Text("a")
        if (b) Text("b")
        if (c) Text("c")
        if (d) Text("d")
        Text("$x $y $z ${w.value}")
    }
}

// Reported: a composable that computes a value has no UI to split, so the advice asks for a plain function.
// The loop 1, its computed `if` with the `&&` 2, and three computed `if`s: 6.
@Composable
fun <!KOTRAIL_COMPOSABLE_TOO_COMPLEX!>presenter<!>(items: List<Item>, filter: String?, mode: Int): String {
    val names = items.map { item -> if (filter != null && item.name.startsWith(filter)) item.name.trim() else "" }
    val prefix = if (mode > 1) names.size.toString() else names.joinToString()
    val suffix = if (names.isEmpty()) "" else names.first()
    return if (mode > 0) prefix + suffix else suffix
}

// Reported: spread, but most of the branches sit inside the effect, and the advice says so. States 1,
// the effect 1 + 1 for writing a state + 2 branches, the Column 1: 6.
@Composable
fun <!KOTRAIL_COMPOSABLE_TOO_COMPLEX!>Router<!>(events: Observable<String>, a: Boolean, b: Boolean) {
    var x by remember { mutableStateOf(0) }
    LaunchedEffect(events) {
        events.collect { e ->
            if (e.isEmpty()) x++ else if (e == "a") x-- else x = 0
        }
    }
    Column {
        if (a) Text("a")
        Text(b.toString())
    }
}

// Reported: the Column spans the whole body and carries every point, but it is the root layout, not
// a block to extract: no hotspot, and the advice does not name it. Six `if`s: 6.
@Composable
fun <!KOTRAIL_COMPOSABLE_TOO_COMPLEX!>Board<!>(a: Boolean, b: Boolean, c: Boolean, d: Boolean, e: Boolean, f: Boolean) {
    Column {
        if (a) Text("a")
        if (b) Text("b")
        if (c) Text("c")
        if (d) Text("d")
        if (e) Text("e")
        if (f) Text("f")
    }
}

/* GENERATED_FIR_TAGS: additiveExpression, andExpression, assignment, classDeclaration, comparisonExpression, const,
elvisExpression, equalityExpression, functionDeclaration, functionalType, ifExpression, incrementDecrementExpression,
integerLiteral, lambdaLiteral, localProperty, nullableType, objectDeclaration, primaryConstructor, propertyDeclaration,
propertyDelegate, setter, smartcast, stringLiteral, suspend, whenExpression, whenWithSubject */

// KOTRAIL_CONFIG: rules.compose.rememberKeys=on
// The constant-key half of rememberKeys. Every lambda reads only what its keys cover, so the
// missing-key half stays quiet here.
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

class Navigator {
    suspend fun observe() {}
}

class Formatter {
    fun format(id: String): String = id
}

class Parser(val id: String)

class Item(val id: String)

object Registry {
    fun lookup(id: String): String = id
}

enum class Mode { Compact, Wide }

const val PAGE_SIZE = 20

class Screen(private val navigator: Navigator) {
    // Reported: a private val of the enclosing class never changes while Content lives.
    @Composable
    fun Content() {
        LaunchedEffect(<!KOTRAIL_REDUNDANT_KEY!>navigator<!>) { navigator.observe() }
    }

    // Reported: the same val read through an explicit this.
    @Composable
    fun ExplicitThis() {
        LaunchedEffect(<!KOTRAIL_REDUNDANT_KEY!>this.navigator<!>) { navigator.observe() }
    }

    // Reported: only config, a companion val; Unit is the idiom for "once".
    @Composable
    fun Configured() {
        LaunchedEffect(Unit, <!KOTRAIL_REDUNDANT_KEY!>config<!>) { navigator.observe() }
    }

    companion object {
        val config = "config"
    }
}

// Reported: an object never changes.
@Composable
fun Lookup(id: String) {
    val text = remember(<!KOTRAIL_REDUNDANT_KEY!>Registry<!>, id) { Registry.lookup(id) }
    Text(text)
}

class Thumbnails {
    fun close() {}
}

class SnackbarHostState {
    suspend fun showSnackbar(message: String) {}
}

// Not reported: an effect keyed on the remembered object it acts on. The object keeps its identity
// only while its remember keeps the keys it has: once that remember gains a key, the keyed effect
// restarts and closes the old object, where DisposableEffect(Unit) would leak it.
@Composable
fun Thumbnailed() {
    val thumbnails = remember { Thumbnails() }
    DisposableEffect(thumbnails) { onDispose(thumbnails::close) }
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(hostState) { hostState.showSnackbar("saved") }
}

// Not reported: a remembered object as the key of another remember, for the same reason.
@Composable
fun Formatted(id: String) {
    val formatter = remember { Formatter() }
    val text = remember(formatter, id) { formatter.format(id) }
    Text(text)
}

// Reported: the literal only; id is a parameter and changes.
@Composable
fun Labeled(id: String) {
    val text = remember(<!KOTRAIL_REDUNDANT_KEY!>"x"<!>, id) { id }
    Text(text)
}

// Reported: a top-level constant and an enum entry.
@Composable
fun Paged(id: String) {
    val text = remember(<!KOTRAIL_REDUNDANT_KEY!>PAGE_SIZE<!>, id) { id }
    val mode = remember(<!KOTRAIL_REDUNDANT_KEY!>Mode.Compact<!>) { Mode.Compact.name }
    Text(text + mode)
}

// Reported: every key is constant; the fix keeps Unit in place of the first.
@Composable
fun AllConstant() {
    val text = remember(<!KOTRAIL_REDUNDANT_KEY!>Registry<!>, <!KOTRAIL_REDUNDANT_KEY!>Mode.Wide<!>, <!KOTRAIL_REDUNDANT_KEY!>PAGE_SIZE<!>) { Registry.lookup(Mode.Wide.name) }
    Text(text)
}

class Colors

class Pane

class Thing

fun isDark(): Boolean = false

@Composable
private fun rememberColors() = remember(isDark()) { Colors() }

@Composable
private fun rememberPane() = remember { Pane() }

// Not reported: an object from a remembering helper is a local like any other, whether the
// helper's remember has a changing key (the colors) or none (the pane).
@Composable
fun Themed() {
    val colors = rememberColors()
    val pane = rememberPane()
    val byColors = remember(colors) { colors.toString() }
    val byPane = remember(pane) { pane.toString() }
    Text(byColors + byPane)
}

// Reported: Registry, a constant key of the remember that makes y. Not reported: x and y, remembered
// objects, whatever the keys of their remember.
@Composable
fun KeyedRemember(id: String) {
    val x = remember(id) { Thing() }
    val y = remember(<!KOTRAIL_REDUNDANT_KEY!>Registry<!>) { Thing() }
    val byX = remember(x) { x.toString() }
    val byY = remember(y) { y.toString() }
    Text(byX + byY)
}

// Not reported: a derived state as a key, as the State itself and as its delegated value.
@Composable
fun Derived(items: List<String>, query: String) {
    val filtered = remember { derivedStateOf { items.filter { it.contains(query) } } }
    val total by remember { derivedStateOf { items.size } }
    val byFiltered = remember(filtered) { filtered.value.size.toString() }
    val byTotal = remember(total) { total.toString() }
    Text(byFiltered + byTotal)
}

// Not reported: the once idiom.
@Composable
fun Once(id: String) {
    LaunchedEffect(Unit) { Registry.lookup(id) }
    val text = remember(true) { Mode.Compact.name }
    Text(text)
}

// Not reported: values that can change while the composable lives.
@Composable
fun Changing(id: String, item: Item, list: List<String>) {
    var count by remember { mutableStateOf(0) }
    val parser = remember(id) { Parser(id) }
    val latest by rememberUpdatedState(id)
    val upper = id.uppercase()
    val byId = remember(id) { id }
    val byCount = remember(count) { count.toString() }
    val byItem = remember(item.id) { item.id }
    val bySize = remember(list.size) { list.size.toString() }
    val byParser = remember(parser) { parser.id }
    val byLocal = remember(upper) { upper }
    val byLatest = remember(latest) { latest }
    DisposableEffect(item) { onDispose { count++ } }
    Text(byId + byCount + byItem + bySize + byParser + byLocal + byLatest)
}

// Not reported: a receiver of an extension composable is handed in by each caller.
@Composable
fun Item.Row() {
    val text = remember(id) { id }
    Text(text)
}

// Not reported: the same shapes outside a composable (the Compose compiler would reject the calls;
// the rule only has to stay out of the way).
class Plain(private val navigator: Navigator) {
    fun keys(id: String) {
        LaunchedEffect(navigator) { navigator.observe() }
        LaunchedEffect(Unit, config) { navigator.observe() }
        val formatter = remember { Formatter() }
        remember(Registry) { Registry.lookup(id) }
        remember(formatter) { formatter.format(id) }
        remember("x", id) { id }
    }

    companion object {
        val config = "config"
    }
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, classDeclaration, companionObject, const, enumDeclaration,
enumEntry, funWithExtensionReceiver, functionDeclaration, incrementDecrementExpression, integerLiteral, lambdaLiteral,
localProperty, nullableType, objectDeclaration, primaryConstructor, propertyDeclaration, propertyDelegate, setter,
stringLiteral, suspend, thisExpression */

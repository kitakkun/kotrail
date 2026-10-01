// KOTRAIL_CONFIG: rules.compose.noCallbackInModel=on
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow

data class UserRow(val name: String, val onClick: () -> Unit)
data class Header(val title: String, val onBack: (() -> Unit)?)
data class Screen(val header: Header, val rows: List<UserRow>)
data class Plain(val id: Long, val name: String)
data class Slotted(val title: String, val trailing: @Composable () -> Unit)

interface HasHandler {
    val onEvent: (String) -> Unit
}

class Inherited(override val onEvent: (String) -> Unit, val label: String) : HasHandler

data class Item(val label: String, val onSelect: () -> Unit)
data class Catalog(val title: String, val items: List<Item>)
data class Toolbar(val title: String, val onBack: () -> Unit)
data class Section<T>(val title: String, val entries: List<T>)

data class Request(val text: String, val reply: () -> Unit)
data class Selection(val id: Long, private val onPick: () -> Unit)
class Picker(val id: Long, private val onPick: () -> Unit)

class Captures {
    private val requests = Channel<Request>()
    private val pending = mutableListOf<Request>()
    private val onDone: () -> Unit = {}
    val count: Int get() = pending.size
}

@Stable
class Mirror(val captures: Captures, val title: String)

class Queue<T>(val name: String) {
    private val channel = Channel<T>()
    fun close(): Boolean = channel.close()
}

data class Event(val name: String, val undo: () -> Unit)
class Feed(val title: String, val events: Flow<Event>)

// Reported: the model holds a callback.
@Composable
fun UserList(<!KOTRAIL_CALLBACK_IN_UI_MODEL!>rows<!>: List<UserRow>) {
    Text("${rows.size}")
}

// Reported: through a nested model, and a nullable function type counts.
@Composable
fun ScreenContent(<!KOTRAIL_CALLBACK_IN_UI_MODEL!>screen<!>: Screen) {
    Text(screen.header.title)
}

// Reported: an inherited property counts.
@Composable
fun InheritedView(<!KOTRAIL_CALLBACK_IN_UI_MODEL!>model<!>: Inherited) {
    Text(model.label)
}

// Not reported: a composable slot in a model is what such a model is for (allowComposableSlots is on by default).
@Composable
fun SlottedView(model: Slotted) {
    Text(model.title)
}

// Reported: a function type in a type argument of a classpath type, named by the type that carries it.
@Composable
fun BannerStrip(<!KOTRAIL_CALLBACK_IN_UI_MODEL!>dismiss<!>: Pair<() -> Unit, String>?) {
    Text(dismiss?.second ?: "")
}

// Reported: the elements of a collection property are part of the model.
@Composable
fun CatalogView(<!KOTRAIL_CALLBACK_IN_UI_MODEL!>catalog<!>: Catalog) {
    Text(catalog.title)
}

// Reported: a function-typed property of the model itself.
@Composable
fun ToolbarView(<!KOTRAIL_CALLBACK_IN_UI_MODEL!>toolbar<!>: Toolbar) {
    Text(toolbar.title)
}

// Reported: a project's generic class carries its type argument through a non-private property.
@Composable
fun SectionView(<!KOTRAIL_CALLBACK_IN_UI_MODEL!>section<!>: Section<Item>) {
    Text(section.title)
}

// Reported, both: each parameter is walked on its own.
@Composable
fun RowPair(<!KOTRAIL_CALLBACK_IN_UI_MODEL!>first<!>: UserRow, <!KOTRAIL_CALLBACK_IN_UI_MODEL!>second<!>: UserRow) {
    Text(first.name + second.name)
}

// Reported: a data class compares its private constructor properties too.
@Composable
fun SelectionView(<!KOTRAIL_CALLBACK_IN_UI_MODEL!>selection<!>: Selection) {
    Text("${selection.id}")
}

// Not reported: a private constructor property of a plain class is neither read nor compared.
@Composable
fun PickerView(picker: Picker) {
    Text("${picker.id}")
}

// Not reported: private properties (a work queue, its backlog, a completion hook) are not what the composable reads.
@Composable
fun MirrorView(mirror: Mirror) {
    Text(mirror.title)
}

// Not reported: a project's generic class whose type argument reaches only a private property.
@Composable
fun QueueView(queue: Queue<Request>) {
    Text(queue.name)
}

// Not reported: the element type of a stream is not part of the model's value.
@Composable
fun FeedView(feed: Feed) {
    Text(feed.title)
}

// Not reported: likewise for a stream taken directly.
@Composable
fun EventLog(events: Flow<Event>) {
    Text("$events")
}

// Not reported: a value model, with the callback on the composable.
@Composable
fun PlainList(rows: List<Plain>, onClick: (Long) -> Unit) {
    Text("${rows.size}")
    onClick(1L)
}

// Not reported: an effect draws nothing.
@Composable
fun RowEffect(row: UserRow) {
    LaunchedEffect(row) { row.onClick() }
}

// Not reported: a composable that returns a value is not a UI emitter.
@Composable
fun rememberRow(row: UserRow): String = row.name

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, data, elvisExpression, functionDeclaration, functionalType,
getter, interfaceDeclaration, lambdaLiteral, nullableType, override, primaryConstructor, propertyDeclaration, safeCall,
stringLiteral, typeParameter */

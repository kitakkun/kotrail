// KOTRAIL_CONFIG: rules.compose.noTrailingCallback=on
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

// Reported: a callback in the trailing position reads as a content slot at the call site.
@Composable
fun ActionCard(title: String, modifier: Modifier = Modifier, <!KOTRAIL_COMPOSABLE_TRAILING_CALLBACK!>onClick<!>: () -> Unit) {
    Text(title, modifier)
    onClick()
}

// Reported: nullable, parameterized, and suspend function types are callbacks too.
@Composable
fun Dialog(title: String, modifier: Modifier = Modifier, <!KOTRAIL_COMPOSABLE_TRAILING_CALLBACK!>onDismiss<!>: (() -> Unit)?) {
    Text(title)
    onDismiss?.invoke()
}

@Composable
fun Field(value: String, modifier: Modifier = Modifier, <!KOTRAIL_COMPOSABLE_TRAILING_CALLBACK!>onValueChange<!>: (String) -> Unit) {
    Text(value)
    onValueChange(value)
}

@Composable
fun Loader(modifier: Modifier = Modifier, <!KOTRAIL_COMPOSABLE_TRAILING_CALLBACK!>onLoad<!>: suspend () -> Unit) {
    Text("loading")
}

// Reported on the interface declaration only; the override below has no say in its signature.
interface Slot {
    @Composable
    fun Render(modifier: Modifier = Modifier, <!KOTRAIL_COMPOSABLE_TRAILING_CALLBACK!>onClick<!>: () -> Unit)
}

class ButtonSlot : Slot {
    @Composable
    override fun Render(modifier: Modifier, onClick: () -> Unit) {
        Text("button")
    }
}

// Not reported: the callback comes before the content slot, nullable content included.
@Composable
fun Card(onClick: () -> Unit, content: @Composable () -> Unit) {
    onClick()
    content()
}

@Composable
fun Panel(onClick: () -> Unit, content: (@Composable () -> Unit)?) {
    onClick()
    content?.invoke()
}

// Not reported: the last parameter is not a function type.
@Composable
fun Toggle(onToggle: (Boolean) -> Unit, enabled: Boolean) {
    onToggle(enabled)
}

// Not reported: composables that return a value are not UI emitters.
@Composable
fun measuredSize(onMeasure: (Int) -> Unit): Int {
    onMeasure(1)
    return 1
}

// Not reported: not a composable; no parameters.
fun plainCard(title: String, onClick: () -> Unit) {
    onClick()
}

@Composable
fun Empty() {
    Text("empty")
}


// Not reported: an effect draws nothing, so its trailing lambda cannot be mistaken for content;
// the trailing-lambda call is the point.
@Composable
fun ErrorEffect(key: Any?, saver: String = "", block: suspend (Throwable) -> Unit) {
    LaunchedEffect(key) { block(IllegalStateException(saver)) }
}

// Not reported: the only function-typed parameter, with no optional block to put it before;
// parameterOrder wants it after the data, and `Row("x") { }` is what the caller writes.
@Composable
fun Row(label: String, onClick: () -> Unit) {
    Text(label)
    onClick()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, functionalType, integerLiteral, interfaceDeclaration,
nullableType, override, safeCall, stringLiteral, suspend */

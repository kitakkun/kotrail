// KOTRAIL_CONFIG: rules.compose.namedCallbackArguments=true
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

// A library-style composable whose callback ended up last (the declaration rule reports it too).
@Composable
fun IconAction(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier) { Text("icon") }
    onClick()
}

@Composable
fun Toggle(checked: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) { Text("$checked") }
    onToggle(!checked)
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) { content() }
}

var clicks = 0

@Composable
fun Screen() {
    Column {
        // Reported: a callback passed as a trailing lambda.
        IconAction <!COMPOSABLE_CALLBACK_AS_TRAILING_LAMBDA!>{ clicks++ }<!>
        IconAction(Modifier) <!COMPOSABLE_CALLBACK_AS_TRAILING_LAMBDA!>{ clicks++ }<!>

        // Not reported: named, or inside the parentheses.
        IconAction(onClick = { clicks++ })
        IconAction(Modifier, { clicks++ })

        // Not reported: the callback is not in the trailing position for this callee.
        Toggle(true, { _ -> clicks++ })

        // Not reported: a real content slot, or a receiver lambda of a DSL builder.
        Panel { Text("content") }
        LazyColumn { items(2) { Text("row") } }

        // Not reported: value-returning and suspend lambdas, and the Compose runtime package.
        val label = remember { "x" }
        LaunchedEffect(label) { clicks++ }
        Text(label)
    }
}

// Not reported: outside a composable the trailing lambda is ordinary Kotlin.
fun plain(block: () -> Unit) {
    block()
}

fun caller() {
    plain { clicks++ }
}

/* GENERATED_FIR_TAGS: functionDeclaration, functionalType, lambdaLiteral, localProperty, propertyDeclaration,
stringLiteral */

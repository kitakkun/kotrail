// KOTRAIL_CONFIG: rules.compose.noCallbackInModel=on
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

data class UserRow(val name: String, val onClick: () -> Unit)
data class Header(val title: String, val onBack: (() -> Unit)?)
data class Screen(val header: Header, val rows: List<UserRow>)
data class Plain(val id: Long, val name: String)
data class Slotted(val title: String, val trailing: @Composable () -> Unit)

interface HasHandler {
    val onEvent: (String) -> Unit
}

class Inherited(override val onEvent: (String) -> Unit, val label: String) : HasHandler

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

// Reported: a composable slot in a model is a callback like any other by default.
@Composable
fun SlottedView(<!KOTRAIL_CALLBACK_IN_UI_MODEL!>model<!>: Slotted) {
    Text(model.title)
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

/* GENERATED_FIR_TAGS: classDeclaration, data, functionDeclaration, functionalType, interfaceDeclaration, lambdaLiteral,
nullableType, override, primaryConstructor, propertyDeclaration */

// KOTRAIL_CONFIG: rules.compose.previewRequired=false, rules.compose.composablesPerFile=false
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// Reported: emits UI (returns Unit) but starts with a lowercase letter.
@Composable
fun <!COMPOSABLE_NAMING!>profileCard<!>(name: String) {
    Text(name)
}

// Reported: returns a value but starts with an uppercase letter.
@Composable
fun <!COMPOSABLE_NAMING!>RememberLabel<!>(name: String): String = remember { "Label: $name" }

// Reported: an explicit Unit return type is still a UI composable.
@Composable
fun <!COMPOSABLE_NAMING!>footer<!>(): Unit {
    Text("footer")
}

// Reported: local composables follow the same convention.
@Composable
fun Screen() {
    @Composable
    fun <!COMPOSABLE_NAMING!>header<!>() {
        Text("header")
    }
    Box {
        header()
    }
}

// Not reported: PascalCase UI composable.
@Composable
fun ProfileCard(name: String) {
    Text(name)
}

// Not reported: camelCase value-returning composable.
@Composable
fun rememberLabel(name: String): String = remember { "Label: $name" }

// Not reported: only the first character decides, so all-caps names pass.
@Composable
fun FAB() {
    Text("+")
}

// Not reported: names that do not start with a letter are left alone.
@Composable
fun _debugOverlay() {
    Text("debug")
}

// Not reported: not composable, whatever the casing.
fun buildLabel(): String = "label"

fun Helper() {
}

// The interface declaration is reported; the override is exempt because its name is fixed.
interface Slot {
    @Composable
    fun <!COMPOSABLE_NAMING!>render<!>()
}

class TextSlot : Slot {
    @Composable
    override fun render() {
        Text("slot")
    }
}

// Not reported: operator functions keep their language-defined names.
class Renderer {
    @Composable
    operator fun invoke() {
        Text("invoke")
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, interfaceDeclaration, lambdaLiteral, localFunction,
operator, override, stringLiteral */

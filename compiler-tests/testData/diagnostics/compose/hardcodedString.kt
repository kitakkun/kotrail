// KOTRAIL_CONFIG: rules.compose.noHardcodedString=on
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.junit.jupiter.api.Test

fun stringResource(id: Int): String = id.toString()

@Composable
fun Card(title: String, message: String, tag: String, modifier: Modifier = Modifier) {
    Text(title, modifier)
    Text(message)
}

@Composable
fun Screen(count: Int, subtitle: String?) {
    // Reported: copy that reaches the screen from a literal.
    Text(<!KOTRAIL_COMPOSABLE_HARDCODED_STRING!>"Submit"<!>)
    Text(text = <!KOTRAIL_COMPOSABLE_HARDCODED_STRING!>"Cancel"<!>)
    Card(title = <!KOTRAIL_COMPOSABLE_HARDCODED_STRING!>"Welcome"<!>, message = <!KOTRAIL_COMPOSABLE_HARDCODED_STRING!>"Nice to see you"<!>, tag = "welcome")

    // Not reported: loaded from resources or built from data.
    Text(stringResource(1))
    Text("$count")
    Text(subtitle ?: stringResource(2))

    // Not reported: punctuation and whitespace are not copy.
    Text("•")
    Text(" ")
    Text("")

    // Not reported: `tag` is not a user-facing parameter.
    Card(title = stringResource(3), message = stringResource(4), tag = "home")
}

// Not reported: preview strings never ship.
@Preview
@Composable
private fun ScreenPreview() {
    Text("Preview")
    Screen(count = 1, subtitle = "Preview subtitle")
}

class ScreenTest {
    // Not reported: test strings never ship.
    @Test
    fun `renders the submit label`() {
        val render: @Composable () -> Unit = { Text("Submit") }
        render.hashCode()
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, elvisExpression, functionDeclaration, functionalType, integerLiteral,
lambdaLiteral, localProperty, nullableType, propertyDeclaration, stringLiteral */

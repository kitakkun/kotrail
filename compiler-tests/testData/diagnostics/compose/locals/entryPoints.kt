// KOTRAIL_CONFIG: rules.compose.compositionLocals=on

package custom

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

val LocalNav = compositionLocalOf<String> { error("No nav provided") }

@Composable
fun Screen() {
    Text(LocalNav.current)
}

class MainActivity : ComponentActivity() {
    fun onCreate() {
        // Reported: the content of setContent is a root, and nothing inside provides the nav.
        <!KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED_AT_ENTRY_POINT!>setContent<!> {
            Screen()
        }

        // Not reported: provided inside the content.
        setContent {
            CompositionLocalProvider(LocalNav provides "nav") {
                Screen()
            }
        }
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, propertyDeclaration, stringLiteral */

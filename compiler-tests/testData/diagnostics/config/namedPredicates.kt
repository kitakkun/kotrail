// KOTRAIL_CONFIG: configFile=compiler-tests/testData/diagnostics/config/namedPredicates.yaml, rules.visibilityPolicy=on, rules.requiredAnnotation=on, rules.forbiddenCall=on
// The named predicates screen, preview and blocking are declared once in namedPredicates.yaml and
// used by three rules; see the file next to this one.

package custom

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

annotation class Preview
annotation class Route

fun <T> runBlocking(block: () -> T): T = block()

// Reported: a screen without its route (policy 'screens', where: screen).
@Composable
fun <!KOTRAIL_REQUIRED_ANNOTATION_MISSING!>HomeScreen<!>(title: String) {
    Text(title)
}

// Not reported: annotated as the policy asks.
@Route
@Composable
fun SettingsScreen(title: String) {
    Text(title)
}

// Reported: a preview must be private (visibilityPolicy.private: preview).
@Preview
@Composable
fun <!KOTRAIL_VISIBILITY_TOO_WIDE!>HomePreview<!>() = HomeScreen("home")

@Preview
@Composable
private fun SettingsPreview() = SettingsScreen("settings")

// Reported: the call 'noBlocking' names the predicate blocking, fqn(custom.runBlocking).
@Composable
fun Loader() {
    val value = <!KOTRAIL_FORBIDDEN_CALL!>runBlocking { 1 }<!>
    Text(value.toString())
}

fun load(): Int = <!KOTRAIL_FORBIDDEN_CALL!>runBlocking { 1 }<!>

// Not reported: a call the predicate does not name.
fun <T> runQuick(block: () -> T): T = block()

fun loadQuick(): Int = runQuick { 1 }

/* GENERATED_FIR_TAGS: annotationDeclaration, functionDeclaration, functionalType, integerLiteral, lambdaLiteral,
localProperty, nullableType, propertyDeclaration, stringLiteral, typeParameter */

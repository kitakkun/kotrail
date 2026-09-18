// KOTRAIL_CONFIG: rules.compose.compositionLocals=on

package custom

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.noLocalProvidedFor
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.tooling.preview.Preview
import com.kitakkun.kotrail.compose.locals.CompositionLocalRoot

class NavController

// Required: the default throws.
val LocalNavController = compositionLocalOf<NavController> { error("No NavController provided") }

// Required: noLocalProvidedFor returns Nothing.
val LocalTheme = staticCompositionLocalOf<String> { noLocalProvidedFor("LocalTheme") }

// Never required: it has a real default.
val LocalTitle = compositionLocalOf { "untitled" }

@Composable
fun TopBar() {
    Text(LocalTitle.current)
    LocalNavController.current
}

@Composable
fun HomeScreen() {
    TopBar()
    LocalTheme.current
}

// Reported twice: the nav controller (read in HomeScreen > TopBar) and the theme (read in HomeScreen).
@CompositionLocalRoot
@Composable
fun <!KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED, KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED!>BareRoot<!>() {
    HomeScreen()
}

// Not reported: both are provided around the whole subtree.
@CompositionLocalRoot
@Composable
fun ProvidedRoot(nav: NavController) {
    CompositionLocalProvider(LocalNavController provides nav, LocalTheme provides "dark") {
        HomeScreen()
    }
}

// Reported once: the theme is still missing.
@CompositionLocalRoot
@Composable
fun <!KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED!>PartialRoot<!>(nav: NavController) {
    CompositionLocalProvider(LocalNavController provides nav) {
        HomeScreen()
    }
}

// Reported: the provider's scope ends before TopBar is called.
@CompositionLocalRoot
@Composable
fun <!KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED!>OutsideRoot<!>(nav: NavController) {
    CompositionLocalProvider(LocalNavController provides nav) {
        Text("inside")
    }
    TopBar()
}

// Not reported: a local with a default never has to be provided.
@CompositionLocalRoot
@Composable
fun TitleRoot() {
    Text(LocalTitle.current)
}

// Not reported: a composable that is not a root only records what it reads.
@Composable
fun NotARoot() {
    LocalNavController.current
}

// Reported twice: a preview is a root, and nothing provides anything to it.
@Preview
@Composable
fun <!KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED, KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED!>HomePreview<!>() {
    HomeScreen()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, propertyDeclaration, stringLiteral */

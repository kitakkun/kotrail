// IGNORE_DEXING
// The library keeps its local private and exposes it through a composable getter; the metadata on
// the getter and on the widget carries the read and the fact that it is required.

// MODULE: lib
// KOTRAIL_CONFIG: rules.compose.compositionLocals=on
// FILE: Theme.kt
package lib.theme

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

private val LocalColors = compositionLocalOf<String> { error("No colors provided") }

object LibTheme {
    val colors: String
        @Composable get() = LocalColors.current
}

@Composable
fun LibTheme(colors: String, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalColors provides colors, content = content)
}

@Composable
fun LibItem() {
    Text(LibTheme.colors)
}

// MODULE: main(lib)
// KOTRAIL_CONFIG: rules.compose.compositionLocals=on
// FILE: Previews.kt
package app

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import lib.theme.LibItem
import lib.theme.LibTheme

// Reported: the library's private local is read in LibItem > colors and never provided.
@Preview
@Composable
fun <!KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED!>BareItemPreview<!>() {
    LibItem()
}

// Not reported: the theme hands its content to the provider.
@Preview
@Composable
fun ThemedItemPreview() {
    LibTheme(colors = "light") { LibItem() }
}

/* GENERATED_FIR_TAGS: functionDeclaration, functionalType, getter, lambdaLiteral, objectDeclaration,
propertyDeclaration, stringLiteral */

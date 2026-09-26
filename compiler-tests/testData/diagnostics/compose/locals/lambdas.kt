// KOTRAIL_CONFIG: rules.compose.compositionLocals=on

package custom

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import com.kitakkun.kotrail.compose.locals.CompositionLocalRoot

val LocalPalette = compositionLocalOf<String> { error("No palette provided") }

// Provides the palette to its content: every invocation of `content` is inside the provider.
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides "light") {
        content()
    }
}

// Provides nothing: the content runs wherever the card is.
@Composable
fun Card(content: @Composable () -> Unit) {
    content()
}

// Invokes its content both outside and inside a provider, so it cannot promise the palette.
@Composable
fun Twice(content: @Composable () -> Unit) {
    content()
    CompositionLocalProvider(LocalPalette provides "dark") {
        content()
    }
}

@Composable
fun Swatch() {
    Text(LocalPalette.current)
}

// Not reported: the lambda runs inside AppTheme's provider.
@CompositionLocalRoot
@Composable
fun ThemedRoot() {
    AppTheme { Swatch() }
}

// Reported: Card provides nothing to its content.
@CompositionLocalRoot
@Composable
fun <!KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED!>PlainRoot<!>() {
    Card { Swatch() }
}

// Not reported: the card sits inside the theme.
@CompositionLocalRoot
@Composable
fun NestedRoot() {
    AppTheme {
        Card { Swatch() }
    }
}

// Not reported: an inline lambda inherits the scope around it.
@CompositionLocalRoot
@Composable
fun LoopRoot() {
    AppTheme {
        listOf(1, 2).forEach { Swatch() }
    }
}

// Reported: one of the two invocations runs without the palette.
@CompositionLocalRoot
@Composable
fun <!KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED!>TwiceRoot<!>() {
    Twice { Swatch() }
}

// Reported: a read in a provider's own argument happens before the provider applies.
@CompositionLocalRoot
@Composable
fun <!KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED!>ArgumentRoot<!>() {
    CompositionLocalProvider(LocalPalette provides LocalPalette.current + "!") {
        Swatch()
    }
}

// Hands its content straight on: the provider invokes it, so the palette is provided to it.
@Composable
fun PassThroughTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides "light", content = content)
}

// The same one level up: a composable handing its content to a theme that provides.
@Composable
fun OuterTheme(content: @Composable () -> Unit) {
    AppTheme(content = content)
}

// Hands its content to a card that provides nothing.
@Composable
fun PassThroughCard(content: @Composable () -> Unit) {
    Card(content)
}

// Not reported: content passed through to a provider is invoked inside it.
@CompositionLocalRoot
@Composable
fun PassThroughRoot() {
    PassThroughTheme { Swatch() }
    OuterTheme { Swatch() }
}

// Reported: passed through to something that provides nothing.
@CompositionLocalRoot
@Composable
fun <!KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED!>PassThroughCardRoot<!>() {
    PassThroughCard { Swatch() }
}

// A private local behind a public composable getter: the getter is a reader like any composable,
// and it is the one that knows the local is required.
private val LocalAccent = compositionLocalOf<String> { error("No accent provided") }

object Theme {
    val accent: String
        @Composable get() = LocalAccent.current
}

@Composable
fun Chip() {
    Text(Theme.accent)
}

// Reported: read in Chip > accent, and never provided.
@CompositionLocalRoot
@Composable
fun <!KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED!>ChipRoot<!>() {
    Chip()
}

// Not reported: provided around it.
@CompositionLocalRoot
@Composable
fun ProvidedChipRoot() {
    CompositionLocalProvider(LocalAccent provides "teal") { Chip() }
}

/* GENERATED_FIR_TAGS: additiveExpression, functionDeclaration, functionalType, integerLiteral, lambdaLiteral,
propertyDeclaration, stringLiteral */

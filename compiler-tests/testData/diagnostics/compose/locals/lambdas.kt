// KOTRAIL_CONFIG: rules.compose.compositionLocals=true

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

/* GENERATED_FIR_TAGS: additiveExpression, functionDeclaration, functionalType, integerLiteral, lambdaLiteral,
propertyDeclaration, stringLiteral */

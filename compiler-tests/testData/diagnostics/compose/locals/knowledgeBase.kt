// KOTRAIL_CONFIG: rules.compose.compositionLocals=on, rules.compose.compositionLocals.platform=custom.LocalContextLike, rules.compose.compositionLocals.required=custom.LocalSoftRequired, rules.compose.compositionLocals.known=custom.LibraryTheme=content:custom.LocalPalette, rules.compose.compositionLocals.known=custom.LibraryWidget=custom.LocalPalette

package custom

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.kitakkun.kotrail.compose.locals.CompositionLocalRoot
import com.kitakkun.kotrail.compose.locals.RequiredCompositionLocal

// Listed as platform-provided: reads are never reported although the default throws.
val LocalContextLike = compositionLocalOf<String> { error("No context") }

// Has a default, but listed in compose.compositionLocals.required.
val LocalSoftRequired = compositionLocalOf { "soft" }

// Has a default, but annotated.
@RequiredCompositionLocal
val LocalAnnotated = compositionLocalOf { "annotated" }

val LocalPalette = compositionLocalOf<String> { error("No palette") }

// The knowledge base says this provides the palette to its content, whatever the body does.
@Composable
fun LibraryTheme(content: @Composable () -> Unit) {
    content()
}

// The knowledge base says this reads the palette, whatever the body does.
@Composable
fun LibraryWidget() {
}

// Not reported: platform-provided.
@CompositionLocalRoot
@Composable
fun ContextRoot() {
    Text(LocalContextLike.current)
}

// Reported: required by configuration.
@CompositionLocalRoot
@Composable
fun <!KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED!>SoftRoot<!>() {
    Text(LocalSoftRequired.current)
}

// Reported: required by annotation.
@CompositionLocalRoot
@Composable
fun <!KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED!>AnnotatedRoot<!>() {
    Text(LocalAnnotated.current)
}

// Not reported: the knowledge base entry for LibraryTheme covers the read in its content.
@CompositionLocalRoot
@Composable
fun ThemedRoot() {
    LibraryTheme { Text(LocalPalette.current) }
}

// Reported: the knowledge base entry for LibraryWidget says it reads the palette.
@CompositionLocalRoot
@Composable
fun <!KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED!>WidgetRoot<!>() {
    LibraryWidget()
}

/* GENERATED_FIR_TAGS: functionDeclaration, functionalType, lambdaLiteral, propertyDeclaration, stringLiteral */

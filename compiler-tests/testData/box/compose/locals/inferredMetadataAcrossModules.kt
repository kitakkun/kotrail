// FIR_IDENTICAL
// DUMP_IR
// KOTRAIL_CONFIG: rules.compose.compositionLocals=on

// Exercises the fact writer end to end: `lib` is compiled to class files, so `main` can only
// understand lib's composables through the compositionLocals facts the plugin wrote into lib's
// facts file, and can only know that LocalNav is required through its requiredCompositionLocal
// fact. The class files carry no annotation; the .facts.txt dump shows both facts.
// A root in `main` that provided nothing would be reported, which fails the test.

// MODULE: lib
// FILE: Lib.kt
package lib

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

val LocalNav = compositionLocalOf<String> { error("No nav provided") }

val LocalTitle = compositionLocalOf { "untitled" }

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNav provides "nav") {
        content()
    }
}

@Composable
fun NavReader() {
    Text(LocalNav.current)
    Text(LocalTitle.current)
}

@Composable
fun Plain() {
}

// MODULE: main(lib)
// FILE: Main.kt
import androidx.compose.runtime.Composable
import com.kitakkun.kotrail.compose.locals.CompositionLocalRoot
import lib.AppTheme
import lib.NavReader
import lib.Plain

// Satisfied through lib's metadata: AppTheme provides LocalNav to its content, which is where
// NavReader reads it; LocalTitle has a default.
@CompositionLocalRoot
@Composable
fun Root() {
    AppTheme { NavReader() }
    Plain()
}

fun box(): String {
    Root()
    return "OK"
}

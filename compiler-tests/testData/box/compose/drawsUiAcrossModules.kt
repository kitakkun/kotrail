// KOTRAIL_CONFIG: rules.compose.previewRequired=on

// Exercises the drawsUi fact end to end: `lib` is compiled to class files, and the .facts.txt
// dump shows the fact the plugin wrote into its facts file for the composable that draws nothing,
// and for no other. `main` reads it back: a public composable that only calls lib's tracker draws
// nothing either and needs no preview; without the fact the tracker would be taken as drawing and
// the missing preview would fail the test. One that calls lib's card draws, and has its preview.

// MODULE: lib
// FILE: Lib.kt
package lib

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun Tracker(key: String) {
    LaunchedEffect(key) { }
}

@Composable
fun Card(title: String) {
    Text(title)
}

@Preview
@Composable
private fun CardPreview() {
    Card("preview")
}

// MODULE: main(lib)
// FILE: Main.kt
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import lib.Card
import lib.Tracker

@Composable
fun Screen() {
    Tracker("screen")
}

@Composable
fun Titled() {
    Card("title")
}

@Preview
@Composable
private fun TitledPreview() {
    Titled()
}

fun box(): String {
    Screen()
    Titled()
    return "OK"
}

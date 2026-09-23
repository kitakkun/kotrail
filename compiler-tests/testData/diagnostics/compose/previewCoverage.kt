// IGNORE_DEXING
// The library declares the composables; the sample module carries the previews and the rule.

// MODULE: lib
// FILE: Cards.kt
package lib.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun Covered(title: String) {
    Text(title)
}

@Composable
fun Missing() {
    Text("missing")
}

@Composable
fun Excluded() {
    Text("excluded")
}

@Composable
internal fun InternalOne() {
    Text("internal")
}

@Composable
private fun Hidden() {
    Text("hidden")
}

@Composable
fun rememberLabel(): String = "label"

fun plain() {}

// FILE: Other.kt
package lib.other

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun Elsewhere() {
    Text("not a listed package")
}

// MODULE: main(lib)
// KOTRAIL_CONFIG: rules.compose.previewCoverage=on, rules.compose.previewCoverage.packages=lib.ui,lib.nowhere, rules.compose.previewCoverage.excludeNames=lib.ui.Excl*
// FILE: Previews.kt
<!KOTRAIL_COMPOSABLE_NOT_COVERED_BY_PREVIEW, KOTRAIL_PREVIEW_COVERAGE_PACKAGE_EMPTY!>package sample<!>

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import lib.ui.Covered

// Reported above, on the package directive: lib.ui.Missing has no preview here, and lib.nowhere
// holds nothing to cover, which is reported rather than passed as covered. Covered is
// called from a preview, Excluded is listed under excludeNames, InternalOne is internal and the
// visibility is public, Hidden is private, rememberLabel returns a value, and Elsewhere is in a
// package that is not listed.

@Preview
@Composable
private fun CoveredPreview() {
    Covered(title = "preview")
}

// FILE: Zzz.kt
package sample

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import lib.ui.Covered

// Not the anchor: Previews.kt comes first by name, so nothing is reported here.
@Preview
@Composable
private fun AnotherPreview() {
    Covered(title = "again")
}

/* GENERATED_FIR_TAGS: functionDeclaration, stringLiteral */

// IGNORE_DEXING
// The library's helper keeps its lambda for the life of an effect; the metadata the plugin writes
// tells the module that calls it.

// MODULE: lib
// KOTRAIL_CONFIG: rules.compose.rememberKeys=warning
// FILE: Effects.kt
package lib.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Observable
import androidx.compose.runtime.collect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

// Reported here (as a warning, so that the library still compiles), and recorded as @InferredEffectCapture(["block"]) for callers.
@Composable
fun ActionEffect(events: Observable<String>, block: (String) -> Unit) {
    <!KOTRAIL_EFFECT_KEY_MISSING_WARNING!>LaunchedEffect(Unit) { events.collect { block(it) } }<!>
}

// Keeps its lambda current: nothing recorded.
@Composable
fun CurrentActionEffect(events: Observable<String>, block: (String) -> Unit) {
    val current by rememberUpdatedState(block)
    LaunchedEffect(Unit) { events.collect { current(it) } }
}

// MODULE: main(lib)
// KOTRAIL_CONFIG: rules.compose.rememberKeys=on
// FILE: Screen.kt
package app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Observable
import lib.effects.ActionEffect
import lib.effects.CurrentActionEffect

// Reported through the library's metadata: the lambda is kept, and it reads onNavigate.
@Composable
fun Screen(events: Observable<String>, onNavigate: (String) -> Unit) {
    ActionEffect(events, <!KOTRAIL_EFFECT_CAPTURED_BY_CALLEE!>{ onNavigate(it) }<!>)
}

// Not reported: the library helper keeps its lambda current.
@Composable
fun SafeScreen(events: Observable<String>, onNavigate: (String) -> Unit) {
    CurrentActionEffect(events) { onNavigate(it) }
}

/* GENERATED_FIR_TAGS: functionDeclaration, functionalType, lambdaLiteral, localProperty, nullableType,
propertyDeclaration, propertyDelegate */

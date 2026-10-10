// KOTRAIL_CONFIG: rules.compose.liveValueRemembered=warning
// DIAGNOSTICS: +KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE_WARNING

// Exercises the identityKeyedParameters fact end to end: `lib` is compiled to class files, and the
// .facts.txt dump shows the fact the plugin wrote into its facts file for the composable that
// keys a filtered copy on its list (the bare name), and for the wrapper that hands its list on to
// it (`transactions@TrafficTab`: the name, then the composable that holds the remember), and for
// no other. `main` reads it back: the SnapshotStateList it hands to either is reported (as a
// warning, so that the box still runs), and its own wrapper of the wrapper is recorded with the
// holder carried over. A snapshot of the list, and a composable that keeps the list itself rather
// than a copy, stay quiet.

// MODULE: lib
// FILE: Lib.kt
package lib

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class Tx(val url: String)

@Composable
fun TrafficTab(transactions: List<Tx>) {
    var query by remember { mutableStateOf("") }
    val visible = remember(transactions, query) { transactions.filter { it.url.contains(query) } }
    Text("${visible.size}")
    query = ""
}

@Composable
fun TrafficPane(transactions: List<Tx>) {
    TrafficTab(transactions)
}

@Composable
fun TrafficHolder(transactions: List<Tx>) {
    val holder = remember(transactions) { Pair(transactions, "all") }
    Text(holder.second)
}

// MODULE: main(lib)
// FILE: Main.kt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import lib.TrafficHolder
import lib.TrafficPane
import lib.TrafficTab
import lib.Tx

@Composable
fun TrafficRoot(transactions: List<Tx>) {
    TrafficPane(transactions)
}

@Composable
fun Screen() {
    val transactions = remember { mutableStateListOf<Tx>() }
    TrafficTab(<!KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE_WARNING!>transactions<!>)
    TrafficPane(<!KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE_WARNING!>transactions<!>)
    TrafficRoot(<!KOTRAIL_LIVE_VALUE_PASSED_TO_REMEMBERING_COMPOSABLE_WARNING!>transactions<!>)
    TrafficTab(transactions.toList())
    TrafficHolder(transactions)
}

fun box(): String {
    Screen()
    return "OK"
}

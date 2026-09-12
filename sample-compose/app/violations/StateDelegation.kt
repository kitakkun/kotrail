// Not part of the sample source set. Copy this file into sample-compose/app/src/main/kotlin/
// and run ./gradlew :sample-compose:app:compileKotlin to see KOTRAIL_PREFER_STATE_DELEGATION fire.
package com.kitakkun.kotrail.sample.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@Composable
fun CounterScreen() {
    val count = remember { mutableStateOf(0) }   // reported: use `var count by remember { ... }`
    Column {
        Text("count = ${count.value}")
        count.value = count.value + 1
    }
}

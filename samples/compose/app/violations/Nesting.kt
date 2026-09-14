// Not part of the sample source set. Copy this file into samples/compose/app/src/main/kotlin/
// and run ./gradlew :samples:compose:app:compileKotlin to see KOTRAIL_COMPOSABLE_NESTING_TOO_DEEP fire.
package com.kitakkun.kotrail.sample.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// Six levels of composable content lambdas; the default limit is five.
@Composable
fun DeeplyNestedScreen() {
    Column {
        Card {
            Column {
                Row {
                    Box {
                        Text("too deep")
                    }
                }
            }
        }
    }
}

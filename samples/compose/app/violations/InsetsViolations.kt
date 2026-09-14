// Not part of the sample source set. Copy this file into samples/compose/app/src/main/kotlin/
// and run ./gradlew :samples:compose:app:compileKotlin to see each insets diagnostic fire.
package com.kitakkun.kotrail.sample.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kitakkun.kotrail.compose.insets.HandlesWindowInsets
import com.kitakkun.kotrail.compose.insets.WindowInsetsType
import com.kitakkun.kotrail.sample.lib.TopBarArea

// KOTRAIL_WINDOW_INSETS_NOT_HANDLED: declares SafeDrawing but only handles status bars.
@HandlesWindowInsets(WindowInsetsType.SafeDrawing)
@Composable
fun MissingScreen() {
    Column(modifier = Modifier.statusBarsPadding()) {
        Text("missing")
    }
}

// KOTRAIL_WINDOW_INSETS_NOT_HANDLED: the callee from :lib handles status bars top only, not the IME.
@HandlesWindowInsets(WindowInsetsType.Ime)
@Composable
fun CrossModuleMissingScreen() {
    TopBarArea { Text("title") }
}

// KOTRAIL_WINDOW_INSETS_HANDLING_UNVERIFIABLE: the insets come from a parameter, so nothing can be proven.
@HandlesWindowInsets(WindowInsetsType.SafeDrawing)
@Composable
fun DynamicInsetsScreen(insets: WindowInsets) {
    Column(modifier = Modifier.windowInsetsPadding(insets)) {
        Text("dynamic")
    }
}

// KOTRAIL_WINDOW_INSETS_HANDLED_TWICE: TopBarArea already handles status bars on top; the Modifier
// passed to it applies safe-drawing padding, which includes status bars again.
@Composable
fun DoublePaddedScreen() {
    TopBarArea(modifier = Modifier.safeDrawingPadding()) { Text("title") }
}

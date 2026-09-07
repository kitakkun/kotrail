// DUMP_IR
// KOTRAIL_CONFIG: rules.compose.previewRequired=false, rules.compose.composablesPerFile=false
// Members of classes and objects also get @InferredWindowInsetsHandling, so contracts in a
// dependent module can be satisfied through them.

// MODULE: lib
// FILE: Lib.kt
package lib

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

class ChatComponents {
    @Composable
    fun Composer() {
        Column(modifier = Modifier.imePadding()) { }
    }

    companion object {
        @Composable
        fun Header() {
            Column(modifier = Modifier.statusBarsPadding()) { }
        }
    }
}

object CutoutArea {
    @Composable
    fun StartEdge() {
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start))) { }
    }
}

// MODULE: main(lib)
// FILE: Main.kt
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.kitakkun.kotrail.compose.insets.HandlesWindowInsets
import com.kitakkun.kotrail.compose.insets.WindowInsetsSide
import com.kitakkun.kotrail.compose.insets.WindowInsetsType
import lib.ChatComponents
import lib.CutoutArea

@HandlesWindowInsets(WindowInsetsType.Ime)
@HandlesWindowInsets(WindowInsetsType.StatusBars)
@HandlesWindowInsets(WindowInsetsType.DisplayCutout, sides = [WindowInsetsSide.Start])
@Composable
fun Screen(components: ChatComponents) {
    Column {
        ChatComponents.Header()
        components.Composer()
        CutoutArea.StartEdge()
    }
}

fun box(): String {
    Screen(ChatComponents())
    return "OK"
}

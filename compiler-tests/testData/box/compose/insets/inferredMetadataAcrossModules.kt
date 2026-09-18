// DUMP_IR
// KOTRAIL_CONFIG: rules.compose.windowInsets=on

// Exercises the IR metadata writer end to end: `lib` is compiled to class files, so `main`
// can only satisfy its contracts through the @InferredWindowInsetsHandling annotation that
// the plugin wrote onto lib's composables. A missing annotation surfaces as
// KOTRAIL_WINDOW_INSETS_NOT_HANDLED in `main`, which fails the test.

// MODULE: lib
// FILE: Lib.kt
package lib

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun TopBarArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))) {
        content()
    }
}

@Composable
fun BottomBarArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .imePadding(),
    ) {
        content()
    }
}

@Composable
fun PlainContent() {
}

// MODULE: main(lib)
// FILE: Main.kt
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.kitakkun.kotrail.compose.insets.HandlesWindowInsets
import com.kitakkun.kotrail.compose.insets.WindowInsetsSide
import com.kitakkun.kotrail.compose.insets.WindowInsetsType
import lib.BottomBarArea
import lib.PlainContent
import lib.TopBarArea

@HandlesWindowInsets(WindowInsetsType.StatusBars, sides = [WindowInsetsSide.Top])
@HandlesWindowInsets(WindowInsetsType.NavigationBars, sides = [WindowInsetsSide.Bottom])
@HandlesWindowInsets(WindowInsetsType.Ime)
@Composable
fun ChatScreen() {
    Column {
        TopBarArea { }
        PlainContent()
        BottomBarArea { }
    }
}

fun box(): String {
    ChatScreen()
    return "OK"
}

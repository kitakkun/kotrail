package com.kitakkun.kotrail.sample.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.kitakkun.kotrail.compose.insets.HandlesWindowInsets
import com.kitakkun.kotrail.compose.insets.WindowInsetsSide
import com.kitakkun.kotrail.compose.insets.WindowInsetsType
import com.kitakkun.kotrail.sample.lib.BottomBarArea
import com.kitakkun.kotrail.sample.lib.PlainContent
import com.kitakkun.kotrail.sample.lib.TopBarArea

/** Satisfied directly: safeDrawingPadding() covers every safe-drawing inset on every side. */
@HandlesWindowInsets(WindowInsetsType.SafeDrawing)
@Composable
fun HomeScreen() {
    Column(modifier = Modifier.safeDrawingPadding()) {
        Text("home")
    }
}

/**
 * Satisfied across the module boundary: TopBarArea (status bars, top) and BottomBarArea
 * (navigation bars bottom, IME all sides) live in :sample-compose:lib and carry inferred metadata.
 */
@HandlesWindowInsets(WindowInsetsType.StatusBars, sides = [WindowInsetsSide.Top])
@HandlesWindowInsets(WindowInsetsType.NavigationBars, sides = [WindowInsetsSide.Bottom])
@HandlesWindowInsets(WindowInsetsType.Ime)
@Composable
fun ChatScreen() {
    Column {
        TopBarArea { Text("title") }
        PlainContent(text = "messages")
        BottomBarArea { Text("composer") }
    }
}

/** Satisfied by an evaluated insets expression with only(). */
@HandlesWindowInsets(WindowInsetsType.DisplayCutout, sides = [WindowInsetsSide.Horizontal])
@Composable
fun CutoutAwareScreen() {
    Column(
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.displayCutout.only(WindowInsetsSides.Left + WindowInsetsSides.Right),
        ),
    ) {
        Text("cutout aware")
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    HomeScreen()
}

@Preview
@Composable
private fun ChatScreenPreview() {
    ChatScreen()
}

@Preview
@Composable
private fun CutoutAwareScreenPreview() {
    CutoutAwareScreen()
}

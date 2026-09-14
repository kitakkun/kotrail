package com.kitakkun.kotrail.sample.lib

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

// None of these declare a contract. Kotrail infers what they handle and records it as
// @InferredWindowInsetsHandling metadata so that :samples:compose:app can be verified.

/** Handles status bars on the top edge only. */
@Composable
fun TopBarArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))) {
        content()
    }
}

/** Handles navigation bars on the bottom edge and the IME on all sides. */
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

/** Handles nothing; callers own the insets. */
@Composable
fun PlainContent(modifier: Modifier = Modifier, text: String) {
    Box(modifier = modifier.fillMaxSize()) {
        Text(text)
    }
}

@Preview
@Composable
private fun TopBarAreaPreview() {
    TopBarArea { Text("title") }
}

@Preview
@Composable
private fun BottomBarAreaPreview() {
    BottomBarArea { Text("composer") }
}

@Preview
@Composable
private fun PlainContentPreview() {
    PlainContent(text = "content")
}

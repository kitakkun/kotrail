// Stubs mirroring androidx.compose.material3. Signatures (including default values) follow
// the real API so that test data reads like production code.
package androidx.compose.material3

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun Text(text: String, modifier: Modifier = Modifier) {
}

object ScaffoldDefaults {
    val contentWindowInsets: WindowInsets get() = WindowInsets.systemBars
}

object TopAppBarDefaults {
    val windowInsets: WindowInsets get() = WindowInsets.systemBars
}

@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars,
    content: @Composable (PaddingValues) -> Unit,
) {
    content(PaddingValues())
}

@Composable
fun TopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.systemBars,
) {
    title()
}

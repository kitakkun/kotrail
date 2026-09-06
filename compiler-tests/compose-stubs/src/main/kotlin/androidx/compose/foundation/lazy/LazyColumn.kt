// Stubs mirroring androidx.compose.foundation.lazy: a non-composable content lambda that
// exposes composable item slots, which the nesting rule must not count twice.
package androidx.compose.foundation.lazy

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

interface LazyItemScope

interface LazyListScope {
    fun item(content: @Composable LazyItemScope.() -> Unit)
    fun items(count: Int, itemContent: @Composable LazyItemScope.(index: Int) -> Unit)
}

private object StubItemScope : LazyItemScope

private class StubListScope : LazyListScope {
    override fun item(content: @Composable LazyItemScope.() -> Unit) {
        StubItemScope.content()
    }

    override fun items(count: Int, itemContent: @Composable LazyItemScope.(index: Int) -> Unit) {
        repeat(count) { StubItemScope.itemContent(it) }
    }
}

@Composable
fun LazyColumn(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    StubListScope().content()
}

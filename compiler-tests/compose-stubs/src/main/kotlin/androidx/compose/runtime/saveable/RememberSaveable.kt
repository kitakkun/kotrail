// Stub mirroring androidx.compose.runtime.saveable.rememberSaveable with its keys (`inputs`).
// The real API also takes a saver and a key, which nothing in the tests needs.
package androidx.compose.runtime.saveable

import androidx.compose.runtime.Composable

@Composable
fun <T : Any> rememberSaveable(vararg inputs: Any?, init: () -> T): T = init()

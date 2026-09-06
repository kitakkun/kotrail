// Stub mirroring androidx.compose.runtime.saveable.rememberSerializable. The real API is
// `inline ... reified T`; here it is a plain generic so that the stub is not inlined across the
// JVM target gap in the tests.
package androidx.compose.runtime.saveable

import androidx.compose.runtime.Composable
import kotlinx.serialization.KSerializer

@Composable
fun <T : Any> rememberSerializable(vararg inputs: Any?, init: () -> T): T = init()

@Composable
fun <T : Any> rememberSerializable(vararg inputs: Any?, serializer: KSerializer<T>, init: () -> T): T = init()

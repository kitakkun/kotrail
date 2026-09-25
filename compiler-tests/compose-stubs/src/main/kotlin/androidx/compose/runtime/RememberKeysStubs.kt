// Stubs for the keyed remember / effect APIs, enough for the rememberKeys rule's fixture. The
// stubs module has no coroutines dependency, so scope and flow stand-ins are local types.
package androidx.compose.runtime

@Composable
fun <T> remember(key1: Any?, calculation: () -> T): T = calculation()

@Composable
fun <T> remember(key1: Any?, key2: Any?, calculation: () -> T): T = calculation()

@Composable
fun <T> remember(vararg keys: Any?, calculation: () -> T): T = calculation()

@Composable
fun LaunchedEffect(key1: Any?, key2: Any?, block: suspend () -> Unit) {
}

@Composable
fun LaunchedEffect(vararg keys: Any?, block: suspend () -> Unit) {
}

interface DisposableEffectResult {
    fun dispose()
}

class DisposableEffectScope {
    fun onDispose(onDisposeEffect: () -> Unit): DisposableEffectResult = object : DisposableEffectResult {
        override fun dispose() = onDisposeEffect()
    }
}

@Composable
fun DisposableEffect(key1: Any?, effect: DisposableEffectScope.() -> DisposableEffectResult) {
}

@Composable
fun <T> produceState(initialValue: T, key1: Any?, producer: suspend MutableState<T>.() -> Unit): State<T> = mutableStateOf(initialValue)

@Composable
fun <T> rememberUpdatedState(newValue: T): State<T> = mutableStateOf(newValue)

/** Stands in for kotlinx.coroutines.CoroutineScope, which the stubs do not depend on. */
class ComposeScope {
    fun launch(block: suspend () -> Unit) {}
}

@Composable
fun rememberCoroutineScope(): ComposeScope = ComposeScope()

/** Stands in for kotlinx.coroutines.flow.Flow. */
class SnapshotFlow<T>(private val block: () -> T) {
    suspend fun collect(action: suspend (T) -> Unit) = action(block())
}

fun <T> snapshotFlow(block: () -> T): SnapshotFlow<T> = SnapshotFlow(block)

/** Stands in for a StateFlow the UI observes. */
class Observable<T>(val value: T)

@Composable
fun <T> Observable<T>.collectAsState(): State<T> = mutableStateOf(value)

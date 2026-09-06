// Stubs mirroring androidx.compose.runtime state APIs, enough for delegation (`by`) to resolve.
package androidx.compose.runtime

import kotlin.reflect.KProperty

interface State<out T> {
    val value: T
}

interface MutableState<T> : State<T> {
    override var value: T
}

private class MutableStateImpl<T>(override var value: T) : MutableState<T>

fun <T> mutableStateOf(value: T): MutableState<T> = MutableStateImpl(value)

fun <T> derivedStateOf(calculation: () -> T): State<T> = MutableStateImpl(calculation())

@Composable
fun <T> rememberSaveable(init: () -> T): T = init()

// Not inline (unlike the real API): the stubs are compiled for a newer JVM than the test
// compiler targets, and inlining across that gap trips INLINE_FROM_HIGHER_PLATFORM.
operator fun <T> State<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value

operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: KProperty<*>, value: T) {
    this.value = value
}

@Composable
fun LaunchedEffect(key1: Any?, block: suspend () -> Unit) {
}

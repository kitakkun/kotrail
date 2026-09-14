// Stubs mirroring androidx.compose.runtime composition locals: enough for `LocalX.current`,
// `LocalX provides value`, and `CompositionLocalProvider` to resolve the way they do in Compose.
package androidx.compose.runtime

// Provided values are kept on a stack so that box tests can run a composition for real: a
// value provided around `content` is visible while `content` runs and gone afterwards.
private val provided = ArrayDeque<Map<CompositionLocal<*>, Any?>>()

abstract class CompositionLocal<T> internal constructor(private val defaultFactory: () -> T) {
    val current: T
        @Composable get() {
            for (frame in provided.asReversed()) {
                if (this in frame) {
                    @Suppress("UNCHECKED_CAST")
                    return frame.getValue(this) as T
                }
            }
            return defaultFactory()
        }
}

abstract class ProvidableCompositionLocal<T> internal constructor(defaultFactory: () -> T) : CompositionLocal<T>(defaultFactory) {
    infix fun provides(value: T): ProvidedValue<T> = ProvidedValue(this, value)

    infix fun providesDefault(value: T): ProvidedValue<T> = ProvidedValue(this, value)
}

class ProvidedValue<T> internal constructor(val compositionLocal: CompositionLocal<T>, val value: T)

private class DynamicProvidableCompositionLocal<T>(defaultFactory: () -> T) : ProvidableCompositionLocal<T>(defaultFactory)

fun <T> compositionLocalOf(defaultFactory: () -> T): ProvidableCompositionLocal<T> = DynamicProvidableCompositionLocal(defaultFactory)

fun <T> staticCompositionLocalOf(defaultFactory: () -> T): ProvidableCompositionLocal<T> = DynamicProvidableCompositionLocal(defaultFactory)

@Composable
fun CompositionLocalProvider(vararg values: ProvidedValue<*>, content: @Composable () -> Unit) {
    provided.addLast(values.associate { it.compositionLocal to it.value })
    try {
        content()
    } finally {
        provided.removeLast()
    }
}

@Composable
fun CompositionLocalProvider(value: ProvidedValue<*>, content: @Composable () -> Unit) {
    CompositionLocalProvider(*arrayOf(value), content = content)
}

fun noLocalProvidedFor(name: String): Nothing = error("CompositionLocal $name not present")

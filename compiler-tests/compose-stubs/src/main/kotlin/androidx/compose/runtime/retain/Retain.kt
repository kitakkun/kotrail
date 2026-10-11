package androidx.compose.runtime.retain

import androidx.compose.runtime.Composable

// Not inline, unlike the real retain: these stubs target a newer JVM than the test compiler, and
// inlining across that gap fails with INLINE_FROM_HIGHER_PLATFORM.
@Composable
fun <T> retain(calculation: () -> T): T = calculation()

@Composable
fun <T> retain(vararg keys: Any?, calculation: () -> T): T = calculation()

interface RetainedEffectResult {
    fun retire()
}

class RetainedEffectScope {
    fun onRetire(onRetiredEffect: () -> Unit): RetainedEffectResult = object : RetainedEffectResult {
        override fun retire() = onRetiredEffect()
    }
}

@Composable
fun RetainedEffect(key1: Any?, effect: RetainedEffectScope.() -> RetainedEffectResult) {
}

@Composable
fun RetainedEffect(vararg keys: Any?, effect: RetainedEffectScope.() -> RetainedEffectResult) {
}

class RetainedValuesStoreRegistry {
    @Composable
    fun LocalRetainedValuesStoreProvider(key: Any?, content: @Composable () -> Unit) {
        content()
    }
}

@Composable
fun retainRetainedValuesStoreRegistry(): RetainedValuesStoreRegistry = RetainedValuesStoreRegistry()

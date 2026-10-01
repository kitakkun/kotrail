package androidx.compose.runtime.retain

interface RetainObserver {
    fun onRetained()

    fun onEnteredComposition()

    fun onExitedComposition()

    fun onRetired()

    fun onUnused()
}

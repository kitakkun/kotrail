package androidx.compose.runtime

interface RememberObserver {
    fun onRemembered()

    fun onForgotten()

    fun onAbandoned()
}

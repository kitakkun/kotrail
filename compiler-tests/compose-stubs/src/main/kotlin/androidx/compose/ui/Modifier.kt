package androidx.compose.ui

import androidx.compose.runtime.Stable

@Stable
interface Modifier {
    fun then(other: Modifier): Modifier = other

    companion object : Modifier
}

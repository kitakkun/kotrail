package androidx.compose.ui

interface Modifier {
    fun then(other: Modifier): Modifier = other

    companion object : Modifier
}

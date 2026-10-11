package androidx.compose.runtime.saveable

import androidx.compose.runtime.Composable

@Composable
fun <T : Any> rememberSaveable(vararg inputs: Any?, init: () -> T): T = init()

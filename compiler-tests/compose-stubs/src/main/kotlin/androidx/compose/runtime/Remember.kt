package androidx.compose.runtime

@Composable
fun <T> remember(calculation: () -> T): T = calculation()

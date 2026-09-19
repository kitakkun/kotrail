// KOTRAIL_CONFIG: rules.compose.noSideEffectInComposition=on, rules.compose.noSideEffectInComposition.functions=ScreenViewModel.load
// A function named under `functions` is reported by name, whatever it returns.
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

class ScreenViewModel {
    fun load() {}
    fun track() {}
}

@Composable
fun Screen(viewModel: ScreenViewModel) {
    // Reported: listed under functions.
    viewModel.<!KOTRAIL_COMPOSABLE_SIDE_EFFECT_IN_COMPOSITION!>load<!>()

    // Not reported: not listed, and returns nothing that marks it as work.
    viewModel.track()

    // Not reported: inside an effect.
    LaunchedEffect(Unit) { viewModel.load() }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral */

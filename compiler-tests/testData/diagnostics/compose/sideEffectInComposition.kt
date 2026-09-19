// KOTRAIL_CONFIG: rules.compose.noSideEffectInComposition=on
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch

class ScreenViewModel(private val scope: CoroutineScope) {
    val events: Flow<Int> = kotlinx.coroutines.flow.emptyFlow()
    fun load() {}
    fun refresh(): Job = scope.launch { }
}

@Composable
fun Action(onClick: () -> Unit) {
    Text("action")
}

@Composable
fun Screen(scope: CoroutineScope, viewModel: ScreenViewModel, items: List<Int>, enabled: Boolean) {
    // Reported: work started in the body runs again on every recomposition.
    scope.<!KOTRAIL_COMPOSABLE_SIDE_EFFECT_IN_COMPOSITION!>launch<!> { viewModel.load() }
    val deferred = scope.<!KOTRAIL_COMPOSABLE_SIDE_EFFECT_IN_COMPOSITION!>async<!> { 1 }
    viewModel.events.<!KOTRAIL_COMPOSABLE_SIDE_EFFECT_IN_COMPOSITION!>launchIn<!>(scope)
    viewModel.<!KOTRAIL_COMPOSABLE_SIDE_EFFECT_IN_COMPOSITION!>refresh<!>()
    if (enabled) {
        scope.<!KOTRAIL_COMPOSABLE_SIDE_EFFECT_IN_COMPOSITION!>launch<!> { }
    }

    // Reported: an inline lambda runs during composition too.
    items.forEach { scope.<!KOTRAIL_COMPOSABLE_SIDE_EFFECT_IN_COMPOSITION!>launch<!> { } }

    // Not reported: an effect runs when its keys change, not on every recomposition.
    LaunchedEffect(Unit) {
        scope.launch { viewModel.load() }
    }

    // Not reported: an event handler runs on the event.
    Action(onClick = { scope.launch { viewModel.load() } })
    val handler = { scope.launch { } }

    // Not reported: a lambda handed to a composable.
    val job = remember { scope.launch { } }

    // Not reported: a plain call that returns nothing is not known to start work.
    viewModel.load()

    // Not reported: a local function only runs when called.
    fun start() {
        scope.launch { }
    }

    Text("${deferred.isActive} ${job.isActive} $handler")
}

// Not reported: not a composable.
fun startWork(scope: CoroutineScope) {
    scope.launch { }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, functionalType, ifExpression, integerLiteral,
lambdaLiteral, localFunction, localProperty, primaryConstructor, propertyDeclaration, stringLiteral */

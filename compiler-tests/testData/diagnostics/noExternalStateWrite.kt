// KOTRAIL_CONFIG: rules.noExternalStateWrite=on, rules.noExternalStateWrite.plainVars=true
// FILE: Owners.kt
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class Scene {
    val capturing: MutableState<Boolean> = mutableStateOf(false)
    var selected by mutableStateOf(0)

    fun render(): String = if (capturing.value) "capture" else "live"

    // Not reported: the owner changes its own state.
    fun startCapture() {
        capturing.value = true
        selected = 1
    }

    // Not reported: a lambda inside the owner is inside it.
    fun later(): () -> Unit = { capturing.value = false }

    companion object {
        // Not reported: the companion is inside the owner.
        fun reset(scene: Scene) {
            scene.capturing.value = false
        }
    }

    // Not reported: a nested class is inside the owner.
    class Controller(private val scene: Scene) {
        fun stop() {
            scene.capturing.value = false
        }
    }
}

data class UiState(val x: Int)

class Repository {
    val state = MutableStateFlow(UiState(0))

    fun load() {
        state.update { it.copy(x = 2) }
    }
}

class Counter {
    var count = 0

    fun describe(): String = "count $count"
}

object Session {
    val active = mutableStateOf(false)

    fun isActive(): Boolean = active.value
}

// A data class with no functions is a bag of values.
data class Point(var x: Int, var y: Int)

// A class with no functions has no method to add.
class Settings {
    var volume = 0
    val muted = mutableStateOf(false)
}

class Holder(val flag: MutableState<Boolean>)

// A data class that declares a function has behavior of its own.
data class Frame(var index: Int) {
    fun render(): String = "frame $index"
}

data class Canvas(val flag: MutableState<Boolean>) {
    fun render(): String = if (flag.value) "busy" else "idle"
}

internal class Draft {
    val text = mutableStateOf("")

    fun isEmpty(): Boolean = text.value.isEmpty()
}

// A public holder and the component that drives it, declared in one file.
class PaneState {
    val fraction: MutableState<Float> = mutableStateOf(0f)
}

fun draggable(onDelta: (Float) -> Unit) {
    onDelta(0f)
}

// Not reported: the owner is declared in the writer's file, so the two are one unit.
fun Pane(state: PaneState) {
    state.fraction.value = 0.5f
    draggable(onDelta = { state.fraction.value = it })
}

// FILE: Writers.kt
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.update
import org.junit.jupiter.api.Test

// The owners live in Owners.kt, so every write below comes from another file.
class CaptureTool(private val scene: Scene, private val repo: Repository, private val counter: Counter) {
    // Reported: another class flips the scene's flag.
    fun capture() {
        <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>scene.capturing.value = true<!>
    }

    // Reported: a delegated state var of another class.
    fun select() {
        <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>scene.selected = 3<!>
    }

    // Reported: updating another class's flow.
    fun refresh() {
        <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>repo.state.update { it.copy(x = 1) }<!>
    }

    // Reported: a plain var of another class, assigned and compound-assigned.
    fun bump() {
        <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>counter.count = 3<!>
        <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>counter.count += 1<!>
        <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>counter.count++<!>
    }

    // Reported: a lambda in another class is still outside the owner.
    fun deferred(): () -> Unit = { <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>scene.capturing.value = false<!> }
}

// Reported: a top-level function is outside every class.
fun stopCapture(scene: Scene) {
    <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>scene.capturing.value = false<!>
}

// Reported: an object's state written from another class.
class Login {
    fun signIn() {
        <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>Session.active.value = true<!>
    }
}

class Editor {
    fun edit(point: Point, settings: Settings, holder: Holder, frame: Frame, canvas: Canvas) {
        // Not reported: a plain var of a bag of values, data class or not.
        point.x = 1
        settings.volume = 2
        // Reported: a state holder is reported on every owner, a bag of values included.
        <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>settings.muted.value = true<!>
        <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>holder.flag.value = true<!>
        // Reported: a data class with a function is not a bag.
        <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>frame.index = 2<!>
        // Reported: a data class's state flag flipped from outside, around the writer's own work.
        <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>canvas.flag.value = true<!>
        canvas.render()
        <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>canvas.flag.value = false<!>
        // Not reported: a local.
        var local = 0
        local += 1
        val localState = mutableStateOf(0)
        localState.value = local
    }
}

class Factory {
    // Not reported: an object this function has just built is still being configured.
    fun build(): Counter = Counter().apply { count = 1 }

    fun buildAlso(): Counter = Counter().also { it.count = 2 }

    fun buildWith(): Scene = with(Scene()) { capturing.value = true; this }

    fun buildLocal(): Counter {
        val counter = Counter()
        counter.count = 3
        return counter
    }

    // Reported: a received object belongs to someone else.
    fun reuse(counter: Counter): Counter {
        <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>counter.count = 4<!>
        return counter.apply { <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>count = 5<!> }
    }
}

// A private top-level class is visible only in this file, so this file owns it.
private class EditingState {
    val draft = mutableStateOf("")
    var cursor = 0

    fun isEmpty(): Boolean = draft.value.isEmpty()
}

// Not reported: a presenter feeding its own file-private state holder.
class EditingPresenter {
    private val state = EditingState()

    fun type(text: String) {
        state.draft.value = text
        state.cursor = text.length
    }
}

// Reported: an internal owner declared in another file is visible to the whole module.
internal class DraftPresenter(private val draft: Draft) {
    fun type(text: String) {
        <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>draft.text.value = text<!>
    }
}

class SceneTest {
    // Not reported: a test reaches into what it checks.
    @Test
    fun capturesWhileRunning() {
        val scene = Scene()
        scene.capturing.value = true
    }
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, classDeclaration, companionObject, data, functionDeclaration,
functionalType, ifExpression, incrementDecrementExpression, integerLiteral, lambdaLiteral, localProperty, nestedClass,
objectDeclaration, primaryConstructor, propertyDeclaration, propertyDelegate, setter, stringLiteral */

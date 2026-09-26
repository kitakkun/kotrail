// KOTRAIL_CONFIG: rules.noExternalStateWrite=on
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.jupiter.api.Test

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

object Session {
    val active = mutableStateOf(false)

    fun isActive(): Boolean = active.value
}

// Reported: an object's state written from another class.
class Login {
    fun signIn() {
        <!KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER!>Session.active.value = true<!>
    }
}

// Not reported: a data class is a bag of values.
data class Point(var x: Int, var y: Int)

// Not reported: a class with no functions has no method to add.
class Settings {
    var volume = 0
    val muted = mutableStateOf(false)
}

class Holder(val flag: MutableState<Boolean>)

class Editor {
    fun edit(point: Point, settings: Settings, holder: Holder) {
        point.x = 1
        settings.volume = 2
        settings.muted.value = true
        holder.flag.value = true
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

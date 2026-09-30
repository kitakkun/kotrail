// KOTRAIL_CONFIG: rules.catchTooBroad=on

// Exercises the recordsFailure fact end to end: `lib` is compiled to class files, and the
// .facts.txt dump shows the marker the plugin wrote into its facts file for the function that
// records its failure before rethrowing it, and for no other. `main` catches that failure with an
// empty broad clause, which the checker accepts, and checks that it was recorded.

// MODULE: lib
// FILE: Scene.kt
package lib

class Scene {
    var failure: Throwable? = null

    fun render(fail: Boolean) {
        try {
            draw(fail)
        } catch (e: Throwable) {
            failure = e
            throw e
        }
    }

    // Rethrows without recording: no fact.
    fun renderUnrecorded(fail: Boolean) {
        try {
            draw(fail)
        } catch (e: Throwable) {
            throw e
        }
    }

    private fun draw(fail: Boolean) {
        if (fail) error("draw failed")
    }
}

// MODULE: main(lib)
// FILE: Main.kt
import lib.Scene

fun box(): String {
    val scene = Scene()
    try {
        scene.render(true)
    } catch (e: Throwable) {
    }
    scene.renderUnrecorded(false)
    return if (scene.failure?.message == "draw failed") "OK" else "FAIL: ${scene.failure}"
}

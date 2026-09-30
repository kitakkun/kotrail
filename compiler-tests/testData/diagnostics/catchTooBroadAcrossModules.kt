// IGNORE_DEXING
// The library's render() records its failure before rethrowing it; the facts file the plugin
// writes tells the module that calls it, whose empty broad catch is then accepted.

// MODULE: lib
// KOTRAIL_CONFIG: rules.catchTooBroad=on
// FILE: Scene.kt
package lib.ui

class State<T>(var value: T)

class Scene {
    val failure = State<Throwable?>(null)

    // Recorded as recordsFailure in the library's facts file, for callers.
    fun render() {
        try {
            draw()
        } catch (e: Throwable) {
            failure.value = e
            throw e
        }
    }

    // Not recorded: it rethrows without recording.
    fun renderUnrecorded() {
        try {
            draw()
        } catch (e: Throwable) {
            throw e
        }
    }

    private fun draw() {}
}

// MODULE: main(lib)
// KOTRAIL_CONFIG: rules.catchTooBroad=on
// FILE: Host.kt
package app

import lib.ui.Scene

fun frame(scene: Scene) {
    // Not reported, through the library's facts file: render() has recorded the failure.
    try {
        scene.render()
    } catch (e: Throwable) {
    }

    // Reported: nothing says renderUnrecorded() records.
    try {
        scene.renderUnrecorded()
    } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Throwable<!>) {
    }
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, functionDeclaration, localProperty, nullableType,
primaryConstructor, propertyDeclaration, tryExpression, typeParameter */

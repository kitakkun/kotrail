// DUMP_IR
// KOTRAIL_CONFIG: rules.preconditions=on

// Exercises the IR metadata writer end to end: `lib` is compiled to class files, and the dump
// shows the @InferredPreconditions annotations the plugin wrote onto its declarations. `main`
// calls them with values that satisfy every contract, so the checker stays quiet and the box
// runs.

// MODULE: lib
// FILE: Lib.kt
package lib

fun retry(times: Int, label: String) {
    require(times >= 0) { "times must not be negative" }
    require(label.isNotBlank())
    println("$label x$times")
}

fun window(start: Int, end: Int) {
    require(start < end)
    println(end - start)
}

class Percent(val value: Int, val note: String) {
    init {
        require(value in 0..100)
    }
}

// Private declarations get no metadata: nothing outside this module can call them.
private fun hidden(n: Int) {
    require(n > 0)
    println(n)
}

// A condition on state other than the parameters is not recorded.
class Counter(var count: Int, val name: String) {
    fun add(delta: Int) {
        require(count + delta >= 0)
        count += delta
    }
}

fun touch() {
    hidden(1)
    Counter(0, "c").add(1)
}

// MODULE: main(lib)
// FILE: Main.kt
import lib.Percent
import lib.retry
import lib.touch
import lib.window

fun box(): String {
    retry(3, "sync")
    window(0, 10)
    val percent = Percent(50, "half")
    touch()
    return if (percent.value == 50) "OK" else "FAIL"
}

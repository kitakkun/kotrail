// DUMP_IR
// KOTRAIL_CONFIG: rules.noOpCall=on

// Exercises the fact writer end to end: `lib` is compiled to class files, and the .facts.txt
// dump shows the noOpWhen facts the plugin wrote into its facts file. `main` calls them with
// arguments that make no guard true, so the checker stays quiet and the box runs.

// MODULE: lib
// FILE: Lib.kt
package lib

import kotlin.time.Duration

fun send(bytes: ByteArray) {
    if (bytes.isEmpty()) return
    println(bytes.size)
}

fun resize(width: Int, height: Int) {
    if (width <= 0) return
    when {
        height <= 0 -> return
        height > 10_000 -> return
    }
    println(width * height)
}

open class Channel {
    // Final, in an open class: recorded.
    fun write(text: String) {
        if (text.isBlank()) return
        println(text)
    }

    // Open: an override may do something, so nothing is recorded.
    open fun push(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        println(bytes.size)
    }
}

// A duration guard is recorded in the same language as the others.
fun pause(timeout: Duration) {
    if (timeout <= Duration.ZERO) return
    println(timeout)
}

// A guard that does work before returning is not recorded.
fun upload(bytes: ByteArray) {
    if (bytes.isEmpty()) {
        println("nothing to upload")
        return
    }
    println(bytes.size)
}

// Private declarations get no metadata: nothing outside this module can call them.
private fun hidden(n: Int) {
    if (n == 0) return
    println(n)
}

fun touch() {
    hidden(1)
}

// MODULE: main(lib)
// FILE: Main.kt
import kotlin.time.Duration.Companion.seconds
import lib.Channel
import lib.pause
import lib.resize
import lib.send
import lib.touch
import lib.upload

fun box(): String {
    send(byteArrayOf(1))
    resize(2, 3)
    Channel().write("ok")
    Channel().push(byteArrayOf(1))
    upload(byteArrayOf(1))
    pause(1.seconds)
    touch()
    return "OK"
}

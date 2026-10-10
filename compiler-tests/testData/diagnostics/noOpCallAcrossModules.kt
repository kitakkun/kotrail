// IGNORE_DEXING
// RENDER_DIAGNOSTICS_FULL_TEXT
// The library's guards are recorded as noOpWhen in its facts file, and the module that calls it
// reads them back, durations included.

// MODULE: lib
// KOTRAIL_CONFIG: rules.noOpCall=on
// FILE: Transport.kt
package lib.net

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

open class Transport {
    // Final, in an open class: subclasses inherit it as it is.
    fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        println(bytes.size)
    }

    // Open: no fact is written, since an override may do something.
    open fun close(code: Int) {
        if (code < 0) return
        println(code)
    }
}

fun log(text: String, level: Int) {
    if (level < 0) return
    if (text.isBlank()) return
    println(text)
}

// A duration in a guard is written as Duration.ZERO or <number>.<unit>, and read back so.
fun pause(timeout: Duration) {
    if (timeout <= Duration.ZERO) return
    println(timeout)
}

fun poll(interval: Duration) {
    if (interval < 500.milliseconds) return
    println(interval)
}

// MODULE: main(lib)
// KOTRAIL_CONFIG: rules.noOpCall=on
// FILE: Client.kt
package app

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import lib.net.Transport
import lib.net.log
import lib.net.pause
import lib.net.poll

fun client(transport: Transport, message: String) {
    // Reported through the library's facts.
    <!KOTRAIL_CALL_DOES_NOTHING!>transport.send(byteArrayOf())<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>log("", 1)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>log(message, -1)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>transport.send(ByteArray(0))<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>pause(Duration.ZERO)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>pause((-1).seconds)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>poll(100.milliseconds)<!>

    // Not reported: the arguments do not make the guard true, or the function is open.
    transport.send(byteArrayOf(1))
    log("ready", 1)
    log(message, 1)
    transport.close(-1)
    pause(1.seconds)
    poll(500.milliseconds)
    poll(1.seconds)
}

/* GENERATED_FIR_TAGS: classDeclaration, comparisonExpression, functionDeclaration, ifExpression, integerLiteral,
stringLiteral */

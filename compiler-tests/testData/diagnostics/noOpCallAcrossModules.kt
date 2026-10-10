// IGNORE_DEXING
// RENDER_DIAGNOSTICS_FULL_TEXT
// The library's guards are written into its metadata as @InferredNoOpWhen, and the module that
// calls it reads them back.

// MODULE: lib
// KOTRAIL_CONFIG: rules.noOpCall=on
// FILE: Transport.kt
package lib.net

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

// MODULE: main(lib)
// KOTRAIL_CONFIG: rules.noOpCall=on
// FILE: Client.kt
package app

import lib.net.Transport
import lib.net.log

fun client(transport: Transport, message: String) {
    // Reported through the library's metadata.
    <!KOTRAIL_CALL_DOES_NOTHING!>transport.send(byteArrayOf())<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>log("", 1)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>log(message, -1)<!>

    // Not reported: the arguments do not make the guard true, or the function is open.
    transport.send(byteArrayOf(1))
    log("ready", 1)
    log(message, 1)
    transport.close(-1)
}

/* GENERATED_FIR_TAGS: classDeclaration, comparisonExpression, functionDeclaration, ifExpression, integerLiteral,
stringLiteral */

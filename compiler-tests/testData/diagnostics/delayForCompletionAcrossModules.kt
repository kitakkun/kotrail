// IGNORE_DEXING
// The library's function starts work and drops the handle; the metadata the plugin writes tells
// the module that calls it.

// MODULE: lib
// KOTRAIL_CONFIG: rules.delayForCompletion=on
// FILE: Manager.kt
package lib.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class Manager(private val scope: CoroutineScope) {
    // Recorded as @InferredStartsAsyncWork for callers.
    fun reconnect() {
        scope.launch { }
    }

    fun send() {}
}

// MODULE: main(lib)
// KOTRAIL_CONFIG: rules.delayForCompletion=on
// FILE: Client.kt
package app

import kotlinx.coroutines.delay
import lib.net.Manager

// Reported through the library's metadata.
suspend fun switch(manager: Manager) {
    manager.reconnect()
    <!KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK!>delay(500)<!>
    manager.send()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration,
suspend */

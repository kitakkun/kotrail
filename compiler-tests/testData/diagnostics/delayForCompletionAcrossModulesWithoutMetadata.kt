// IGNORE_DEXING
// The library is built with inferredMetadata=false, as a published library whose consumers do not
// use Kotrail would be: its own analysis is unchanged, but it writes no fact for the module that
// calls it, which then sees it as a library compiled without Kotrail.

// MODULE: lib
// KOTRAIL_CONFIG: rules.delayForCompletion=warning, inferredMetadata=false
// FILE: Manager.kt
package lib.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class Manager(private val scope: CoroutineScope) {
    // Not recorded for callers.
    fun reconnect() {
        scope.launch { }
    }

    fun send() {}

    // Still reported within the library (at warning severity, so that the library compiles).
    suspend fun restart() {
        reconnect()
        <!KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK_WARNING!>delay(500)<!>
        send()
    }
}

// MODULE: main(lib)
// KOTRAIL_CONFIG: rules.delayForCompletion=on
// FILE: Client.kt
package app

import kotlinx.coroutines.delay
import lib.net.Manager

// Nothing to read: the library wrote no metadata.
suspend fun switch(manager: Manager) {
    manager.reconnect()
    delay(500)
    manager.send()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration,
suspend */

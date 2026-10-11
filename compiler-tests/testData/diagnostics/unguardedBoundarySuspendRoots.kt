// IGNORE_DEXING
// A suspend function returns its failure to the coroutine that called it, so neither a suspend override
// nor a lambda converted to an interface whose function is suspend is a boundary.

// MODULE: library
// FILE: Updates.kt
package library

interface HostUpdateService {
    suspend fun check()
}

interface HostUpdateListener {
    fun onUpdate()
}

fun interface UpdateHandler {
    suspend fun handle()
}

fun interface UpdateCallback {
    fun handle()
}

fun onUpdates(handler: UpdateHandler) {}

fun onUpdatesNow(callback: UpdateCallback) {}

class Releases {
    fun latest(): String = ""
}

// MODULE: main(library)
// KOTRAIL_CONFIG: rules.unguardedBoundary=on
// FILE: Updater.kt
package app

import java.io.IOException
import library.HostUpdateListener
import library.HostUpdateService
import library.Releases
import library.onUpdates
import library.onUpdatesNow

class DefaultHostUpdateService(private val releases: Releases) : HostUpdateService {
    // Not reported: the coroutine that calls check receives whatever this does not catch.
    override suspend fun check() {
        try {
            releases.latest()
        } catch (e: IOException) {
        }
    }
}

class HostUpdateLogger(private val releases: Releases) : HostUpdateListener {
    // Reported: the library calls onUpdate and forgets what escapes it.
    override fun onUpdate() {
        try {
            releases.latest()
        } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IOException<!>) {
        }
    }
}

fun register(releases: Releases) {
    // Not reported: converted to an interface whose function is suspend, the lambda is a suspend function too.
    onUpdates {
        try {
            releases.latest()
        } catch (e: IOException) {
        }
    }

    // Reported: converted to an interface whose function is not suspend.
    onUpdatesNow {
        try {
            releases.latest()
        } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IOException<!>) {
        }
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, funInterface, functionDeclaration, interfaceDeclaration, lambdaLiteral,
localProperty, override, primaryConstructor, propertyDeclaration, samConversion, stringLiteral, suspend, tryExpression */

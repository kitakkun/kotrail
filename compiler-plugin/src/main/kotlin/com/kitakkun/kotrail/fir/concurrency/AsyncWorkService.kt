package com.kitakkun.kotrail.fir.concurrency

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol

/**
 * Stub: to be implemented. Per-session analysis of which non-suspending functions start
 * asynchronous work without handing the caller a way to wait for it; from the body for a source
 * declaration, from the inferred metadata annotation for one on the classpath.
 */
class AsyncWorkService(session: FirSession) : FirExtensionSessionComponent(session) {
    /** Whether [symbol] starts asynchronous work its caller cannot wait for. */
    fun startsAsyncWork(symbol: FirNamedFunctionSymbol): Boolean = false
}

val FirSession.asyncWorkService: AsyncWorkService by FirSession.sessionComponentAccessor()

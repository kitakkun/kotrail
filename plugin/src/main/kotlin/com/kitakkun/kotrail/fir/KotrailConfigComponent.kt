package com.kitakkun.kotrail.fir

import com.kitakkun.kotrail.KotrailConfig
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent

/** Exposes the plugin configuration to checkers through the session. */
class KotrailConfigComponent(session: FirSession, val config: KotrailConfig) : FirExtensionSessionComponent(session) {
    companion object {
        fun getFactory(config: KotrailConfig): Factory = Factory { session -> KotrailConfigComponent(session, config) }
    }
}

val FirSession.kotrailConfig: KotrailConfig
    get() = kotrailConfigComponent.config

private val FirSession.kotrailConfigComponent: KotrailConfigComponent by FirSession.sessionComponentAccessor()

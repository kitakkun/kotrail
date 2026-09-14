package com.kitakkun.kotrail.fir

import com.kitakkun.kotrail.KotrailConfig
import com.kitakkun.kotrail.fir.compose.insets.WindowInsetsHandlingService
import com.kitakkun.kotrail.fir.compose.locals.CompositionLocalService
import com.kitakkun.kotrail.fir.preconditions.PreconditionService
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class KotrailFirExtensionRegistrar(private val config: KotrailConfig) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +KotrailConfigComponent.getFactory(config)
        +::WindowInsetsHandlingService
        +::CompositionLocalService
        +::PreconditionService
        +::KotrailCheckersExtension
        registerDiagnosticContainers(KotrailDiagnostics)
    }
}

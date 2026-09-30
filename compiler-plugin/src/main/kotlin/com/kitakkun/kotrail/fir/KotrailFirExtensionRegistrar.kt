package com.kitakkun.kotrail.fir

import com.kitakkun.kotrail.KotrailConfig
import com.kitakkun.kotrail.fir.checkers.NamedConditionIndex
import com.kitakkun.kotrail.fir.compose.DrawsUiService
import com.kitakkun.kotrail.fir.compose.effects.EffectCaptureService
import com.kitakkun.kotrail.fir.concurrency.AsyncWorkService
import com.kitakkun.kotrail.fir.errors.RecordsFailureService
import com.kitakkun.kotrail.fir.inferred.FactsFileComponent
import com.kitakkun.kotrail.inferred.FactsFileLookup
import com.kitakkun.kotrail.fir.compose.insets.WindowInsetsHandlingService
import com.kitakkun.kotrail.fir.compose.locals.CompositionLocalService
import com.kitakkun.kotrail.fir.memory.NativeAllocationService
import com.kitakkun.kotrail.fir.native.ThrowsService
import com.kitakkun.kotrail.fir.noop.NoOpService
import com.kitakkun.kotrail.fir.preconditions.PreconditionService
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class KotrailFirExtensionRegistrar(private val config: KotrailConfig, private val factsFiles: FactsFileLookup) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +KotrailConfigComponent.getFactory(config)
        +FactsFileComponent.getFactory(factsFiles)
        +::WindowInsetsHandlingService
        +::EffectCaptureService
        +::AsyncWorkService
        +::CompositionLocalService
        +::PreconditionService
        +::NoOpService
        +::ThrowsService
        +::NativeAllocationService
        +::DrawsUiService
        +::RecordsFailureService
        +::NamedConditionIndex
        +::KotrailCheckersExtension
        registerDiagnosticContainers(KotrailDiagnostics)
    }
}

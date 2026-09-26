@file:OptIn(ExperimentalCompilerApi::class)

package com.kitakkun.kotrail

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailFirExtensionRegistrar
import com.kitakkun.kotrail.ir.compose.effects.InferredEffectCaptureMetadataWriter
import com.kitakkun.kotrail.ir.compose.insets.InferredWindowInsetsMetadataWriter
import com.kitakkun.kotrail.ir.concurrency.InferredStartsAsyncWorkMetadataWriter
import com.kitakkun.kotrail.ir.memory.InferredNativeAllocationMetadataWriter
import com.kitakkun.kotrail.ir.compose.locals.InferredCompositionLocalsMetadataWriter
import com.kitakkun.kotrail.ir.preconditions.InferredPreconditionsMetadataWriter
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

class KotrailComponentRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = KotrailNames.PLUGIN_ID
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val config = KotrailConfig.from(configuration)
        if (!config.enabled) return

        FirExtensionRegistrarAdapter.registerExtension(KotrailFirExtensionRegistrar(config))
        if (config.isEnabled(KotrailRule.COMPOSE_WINDOW_INSETS)) {
            IrGenerationExtension.registerExtension(InferredWindowInsetsMetadataWriter())
        }
        if (config.isEnabled(KotrailRule.COMPOSE_COMPOSITION_LOCALS)) {
            IrGenerationExtension.registerExtension(InferredCompositionLocalsMetadataWriter())
        }
        if (config.isEnabled(KotrailRule.PRECONDITIONS)) {
            IrGenerationExtension.registerExtension(InferredPreconditionsMetadataWriter())
        }
        if (config.isEnabled(KotrailRule.COMPOSE_REMEMBER_KEYS)) {
            IrGenerationExtension.registerExtension(InferredEffectCaptureMetadataWriter())
        }
        if (config.isEnabled(KotrailRule.NATIVE_ALLOCATION_IN_LOOP)) {
            IrGenerationExtension.registerExtension(InferredNativeAllocationMetadataWriter())
        }
        if (config.isEnabled(KotrailRule.DELAY_FOR_COMPLETION)) {
            IrGenerationExtension.registerExtension(InferredStartsAsyncWorkMetadataWriter())
        }
    }
}

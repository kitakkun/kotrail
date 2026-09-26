@file:OptIn(ExperimentalCompilerApi::class)

package com.kitakkun.kotrail

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailFirExtensionRegistrar
import com.kitakkun.kotrail.ir.inferred.InferredFacts
import com.kitakkun.kotrail.ir.inferred.InferredMetadataWriter
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
        // Every inferred fact whose rule is on is written as metadata for the modules that compile against this one.
        IrGenerationExtension.registerExtension(
            InferredMetadataWriter(
                functionFacts = InferredFacts.functionFacts.filter { config.isEnabled(it.rule) },
                propertyFacts = InferredFacts.propertyFacts.filter { config.isEnabled(it.rule) },
                classFacts = InferredFacts.classFacts.filter { config.isEnabled(it.rule) },
            ),
        )
    }
}

@file:OptIn(ExperimentalCompilerApi::class)

package com.kitakkun.kotrail

import com.kitakkun.kotrail.fir.KotrailFirExtensionRegistrar
import com.kitakkun.kotrail.inferred.FactsFileIndex
import com.kitakkun.kotrail.inferred.FactsFileLookup
import com.kitakkun.kotrail.ir.inferred.FactsFileOutput
import com.kitakkun.kotrail.ir.inferred.InferredFacts
import com.kitakkun.kotrail.ir.inferred.InferredMetadataWriter
import com.kitakkun.kotrail.ir.inferred.SurfaceDigests
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import java.io.File

class KotrailComponentRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = KotrailNames.PLUGIN_ID
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val config = KotrailConfig.from(configuration)
        if (!config.enabled) return

        val factsFiles = FactsFileLookup(
            dependencies = FactsFileIndex { config.facts.map(::File) },
            own = FactsFileIndex { listOfNotNull(config.ownFacts).map(::File) },
        )
        FirExtensionRegistrarAdapter.registerExtension(KotrailFirExtensionRegistrar(config, factsFiles))
        if (!config.inferredMetadata) {
            // A facts file an earlier compilation left at factsOut would otherwise be published or
            // read with stale facts.
            config.factsOut?.let { File(it).delete() }
            return
        }
        IrGenerationExtension.registerExtension(
            InferredMetadataWriter(
                functionFacts = InferredFacts.functionFacts.filter { it.isOn(config) },
                propertyFacts = InferredFacts.propertyFacts.filter { it.isOn(config) },
                classFacts = InferredFacts.classFacts.filter { it.isOn(config) },
                factsFile = FactsFileOutput(
                    file = config.factsOut?.let(::File),
                    module = { configuration.get(CommonConfigurationKeys.MODULE_NAME) },
                    rootDir = config.rootDir,
                ),
                pluginMetadata = config.icMetadata,
                surfaceDigests = surfaceDigests(config),
            ),
        )
    }

    /**
     * Whether the classes get the digest of what the model rules walk: when one of them is on. All but
     * `narrowModelParameters` with `scope: all` walk only from composables, which a module without Compose has none of.
     */
    private fun surfaceDigests(config: KotrailConfig): SurfaceDigests? {
        val narrow = config.isEnabled(KotrailRule.NARROW_MODEL_PARAMETERS)
        if (narrow && config.narrowModelParameters.scope == NarrowModelParametersScope.ALL) return SurfaceDigests(needsCompose = false)
        val composeWalks = narrow || config.isEnabled(KotrailRule.COMPOSE_NO_CALLBACK_IN_MODEL) || config.isEnabled(KotrailRule.COMPOSE_NO_UNSTABLE_PARAMETER)
        return if (composeWalks) SurfaceDigests(needsCompose = true) else null
    }
}

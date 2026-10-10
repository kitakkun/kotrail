@file:OptIn(ExperimentalCompilerApi::class)

package com.kitakkun.kotrail

import com.kitakkun.kotrail.fir.KotrailFirExtensionRegistrar
import com.kitakkun.kotrail.inferred.FactsFileIndex
import com.kitakkun.kotrail.ir.inferred.FactsFileOutput
import com.kitakkun.kotrail.ir.inferred.InferredFacts
import com.kitakkun.kotrail.ir.inferred.InferredMetadataWriter
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

        // The facts files of the dependencies, handed over by the build tool, read on first use.
        val factsFiles = FactsFileIndex { config.facts.map(::File) }
        FirExtensionRegistrarAdapter.registerExtension(KotrailFirExtensionRegistrar(config, factsFiles))
        // Every inferred fact whose rule is on is written for the modules that compile against this one,
        // unless the module opted out (a published library whose consumers do not use Kotrail). A facts file an
        // earlier compilation left at factsOut goes with it, so that nothing stale is published or read. Otherwise the
        // writer is registered whatever rules are on: on the JVM it writes the facts file on every compilation, the
        // header alone when there are no facts, so that the file the Gradle plugin publishes always exists.
        if (!config.inferredMetadata) {
            config.factsOut?.let { File(it).delete() }
            return
        }
        IrGenerationExtension.registerExtension(
            InferredMetadataWriter(
                functionFacts = InferredFacts.functionFacts.filter { config.isEnabled(it.rule) },
                propertyFacts = InferredFacts.propertyFacts.filter { config.isEnabled(it.rule) },
                classFacts = InferredFacts.classFacts.filter { config.isEnabled(it.rule) },
                factsFile = FactsFileOutput(
                    file = config.factsOut?.let(::File),
                    module = { configuration.get(CommonConfigurationKeys.MODULE_NAME) },
                    rootDir = config.rootDir,
                ),
            ),
        )
    }
}

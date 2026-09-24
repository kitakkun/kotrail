@file:OptIn(ExperimentalCompilerApi::class)

package com.kitakkun.kotrail

import com.kitakkun.kotrail.config.ConfigSchema
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * The plugin's command line: `configFile` (repeatable) and one option per path in the
 * configuration tree, named as the YAML key path with the rule's own dots kept
 * (`rules.compose.nesting.maxDepth`). Every option is passed through as `name=value` and read
 * by [KotrailConfig] the way the file would read it, so the two never disagree.
 */
class KotrailCommandLineProcessor : CommandLineProcessor {
    private val configFileOption = CliOption(
        optionName = "configFile",
        valueDescription = "<path>",
        description = "Path to a kotrail.yaml with rule settings. May be given more than once: a later file " +
            "overrides the entries of an earlier one, and explicit options take precedence over them all",
        required = false,
        allowMultipleOccurrences = true,
    )

    private val fixesDirOption = CliOption(
        optionName = "fixesDir",
        valueDescription = "<directory>",
        description = "Directory to record the fixes of the reported diagnostics in, one JSON-lines file per " +
            "source file, for the Gradle plugin's kotrailFix task or another tool to apply",
        required = false,
    )

    private val composablesDirOption = CliOption(
        optionName = "composablesDir",
        valueDescription = "<directory>",
        description = "Directory to record the UI composables this compilation declares in, one JSON-lines file per " +
            "source file, for the compose.previewCoverage rule of an associated compilation",
        required = false,
    )

    private val associatedComposablesDirOption = CliOption(
        optionName = "associatedComposablesDir",
        valueDescription = "<directory>",
        description = "A composablesDir of a compilation this one is associated with (its main, for a preview or test " +
            "compilation), whose composables compose.previewCoverage checks here. May be given more than once",
        required = false,
        allowMultipleOccurrences = true,
    )

    private val unloadableDirOption = CliOption(
        optionName = "unloadableDir",
        valueDescription = "<directory>",
        description = "Directory to record the outbound references the unloadableCode rule looks for (ThreadLocals, unscoped " +
            "registrations), one JSON-lines file per source file, for a compilation that bundles this one to report",
        required = false,
    )

    private val bundledUnloadableDirOption = CliOption(
        optionName = "bundledUnloadableDir",
        valueDescription = "<directory>",
        description = "An unloadableDir of a module on this compilation's runtime class path (its main compilation's " +
            "record directory); with unloadableCode on, what it recorded is reported here. May be given more than once",
        required = false,
        allowMultipleOccurrences = true,
    )

    private val rootDirOption = CliOption(
        optionName = "rootDir",
        valueDescription = "<directory>",
        description = "The build's root directory; a message that names a file of another module gives its path relative to it",
        required = false,
    )

    private val treeOptions: List<CliOption> = KotrailConfig.OPTION_NAMES.map { (name, kind) ->
        val isRuleShorthand = name.startsWith("rules.") && ConfigSchema.ruleByKey(name.removePrefix("rules.")) != null
        val value = when {
            isRuleShorthand -> "<off|on|error|warning>"
            kind == ConfigSchema.Kind.BOOLEAN -> "<true|false>"
            kind == ConfigSchema.Kind.INT -> "<int>"
            kind == ConfigSchema.Kind.ENUM -> "<value>"
            kind == ConfigSchema.Kind.LIST -> "<a,b,...>"
            kind == ConfigSchema.Kind.ENTRIES -> "<name>=<value>"
            kind == ConfigSchema.Kind.PREDICATE || kind == ConfigSchema.Kind.CALL_PREDICATE -> "<predicate>"
            else -> "<text>"
        }
        CliOption(
            optionName = name,
            valueDescription = value,
            description = "The $name entry of kotrail.yaml; an empty value unsets it",
            required = false,
            allowMultipleOccurrences = kind == ConfigSchema.Kind.ENTRIES,
        )
    }

    override val pluginId: String = KotrailNames.PLUGIN_ID
    override val pluginOptions: Collection<CliOption> = listOf(configFileOption, fixesDirOption, composablesDirOption, associatedComposablesDirOption, unloadableDirOption, bundledUnloadableDirOption, rootDirOption) + treeOptions

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        val name = option.optionName
        when {
            name == configFileOption.optionName -> configuration.add(KotrailConfigurationKeys.CONFIG_FILE, value)
            name == fixesDirOption.optionName -> configuration.put(KotrailConfigurationKeys.FIXES_DIR, value)
            name == composablesDirOption.optionName -> configuration.put(KotrailConfigurationKeys.COMPOSABLES_DIR, value)
            name == associatedComposablesDirOption.optionName -> configuration.add(KotrailConfigurationKeys.ASSOCIATED_COMPOSABLES_DIRS, value)
            name == unloadableDirOption.optionName -> configuration.put(KotrailConfigurationKeys.UNLOADABLE_DIR, value)
            name == bundledUnloadableDirOption.optionName -> configuration.add(KotrailConfigurationKeys.BUNDLED_UNLOADABLE_DIRS, value)
            name == rootDirOption.optionName -> configuration.put(KotrailConfigurationKeys.ROOT_DIR, value)
            KotrailConfig.optionPath(name) != null -> configuration.add(KotrailConfigurationKeys.OPTIONS, "$name=$value")
            else -> throw CliOptionProcessingException("Unknown option: $name")
        }
    }
}

@file:OptIn(ExperimentalCompilerApi::class)

package com.kitakkun.kotrail

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

class KotrailCommandLineProcessor : CommandLineProcessor {
    private fun option(
        name: String,
        value: String,
        description: String,
        allowMultipleOccurrences: Boolean = false,
    ) = CliOption(
        optionName = name,
        valueDescription = value,
        description = description,
        required = false,
        allowMultipleOccurrences = allowMultipleOccurrences,
    )

    private val configFileOption = option(
        "configFile", "<path>",
        "Path to a .properties file with rule settings. May be given more than once: a later file " +
            "overrides the entries of an earlier one, and explicit options take precedence over them all",
        allowMultipleOccurrences = true,
    )
    private val enabledOption = option(KotrailConfig.KEY_ENABLED, "<true|false>", "Whether the plugin should run")
    private val noteOption = option(
        KotrailConfig.KEY_NOTE, "<text>",
        "Text appended to every Kotrail message, for the project's own reason or reference",
    )
    private val maxNestingOption = option(
        KotrailConfig.KEY_COMPOSE_MAX_NESTING, "<int>",
        "Maximum nesting depth of composable calls inside one composable body " +
            "(default ${KotrailConfig.DEFAULT_COMPOSE_MAX_NESTING}, 0 disables)",
    )
    private val trailingLambdaAllowedPackagesOption = option(
        KotrailConfig.KEY_TRAILING_LAMBDA_ALLOWED_PACKAGES, "<package,package,...>",
        "Packages whose composables may take a callback as a trailing lambda " +
            "(default ${KotrailConfig.DEFAULT_TRAILING_LAMBDA_ALLOWED_PACKAGES.joinToString(",")})",
    )
    private val previewRequireForOption = option(
        KotrailConfig.KEY_PREVIEW_REQUIRE_FOR, "<public|internal|all>",
        "Which UI composables must have a @Preview in the same file (default ${KotrailConfig.DEFAULT_PREVIEW_REQUIRE_FOR.key})",
    )
    private val maxComposablesPerFileOption = option(
        KotrailConfig.KEY_MAX_COMPOSABLES_PER_FILE, "<int>",
        "Maximum non-private UI composables declared in one file, previews excluded " +
            "(default ${KotrailConfig.DEFAULT_MAX_COMPOSABLES_PER_FILE}, 0 disables)",
    )
    private val serializationRequiredForOption = option(
        KotrailConfig.KEY_SERIALIZATION_REQUIRED_FOR, "<fqName,fqName,...>",
        "Callables whose type arguments must be serializable with kotlinx.serialization, in addition to @MustBeSerializable " +
            "(default ${KotrailConfig.DEFAULT_SERIALIZATION_REQUIRED_FOR.joinToString(",")})",
    )
    private val maxUnusedModelPropertiesOption = option(
        KotrailConfig.KEY_NARROW_MODEL_MAX_UNUSED, "<int>",
        "How many properties of a data-class parameter may stay unread before the parameter is reported as too wide " +
            "(default ${KotrailConfig.DEFAULT_MAX_UNUSED_MODEL_PROPERTIES})",
    )
    private val narrowModelScopeOption = option(
        KotrailConfig.KEY_NARROW_MODEL_SCOPE, "<composables|all>",
        "Which functions the narrow-model-parameters rule inspects (default ${KotrailConfig.DEFAULT_NARROW_MODEL_SCOPE.key})",
    )
    private val referenceFormsOption = option(
        KotrailConfig.KEY_REFERENCE_FORMS, "<topLevel,bound,typeQualified>",
        "Comma-separated reference forms the prefer-function-references rule asks for " +
            "(default ${KotrailConfig.DEFAULT_REFERENCE_FORMS.joinToString(",") { it.key }})",
    )
    private val commentMaxLinesOption = option(
        KotrailConfig.KEY_COMMENT_MAX_LINES, "<int>",
        "Maximum lines for a block comment or a run of consecutive // lines " +
            "(default ${KotrailConfig.DEFAULT_COMMENT_MAX_LINES}, 0 for unlimited)",
    )
    private val kdocMaxLinesOption = option(
        KotrailConfig.KEY_KDOC_MAX_LINES, "<int>",
        "Maximum lines for a KDoc comment (default ${KotrailConfig.DEFAULT_KDOC_MAX_LINES}, 0 for unlimited)",
    )
    private val fqnAllowOption = option(
        KotrailConfig.KEY_FQN_ALLOW, "<package,package,...>",
        "Comma-separated package prefixes whose members may be referenced fully qualified",
    )
    private val forbiddenFunctionsOption = option(
        KotrailConfig.KEY_FORBIDDEN_FUNCTIONS, "<fqName,fqName,...>",
        "Comma-separated fully qualified callables that must not be called",
    )
    private val minSameTypeArgumentsOption = option(
        KotrailConfig.KEY_MIN_SAME_TYPE_ARGUMENTS, "<int>",
        "How many positional arguments of the same type require named arguments " +
            "(default ${KotrailConfig.DEFAULT_MIN_SAME_TYPE_ARGUMENTS})",
    )

    private val functionMaxLinesOption = option(
        KotrailConfig.KEY_FUNCTION_MAX_LINES, "<int>",
        "Most lines of code a function body may have (default ${KotrailConfig.DEFAULT_FUNCTION_MAX_LINES}, 0 for unlimited)",
    )
    private val composableMaxLinesOption = option(
        KotrailConfig.KEY_COMPOSABLE_MAX_LINES, "<int>",
        "Most lines of code a @Composable function body may have " +
            "(default ${KotrailConfig.DEFAULT_COMPOSABLE_MAX_LINES}, 0 for unlimited)",
    )
    private val testAnnotationsOption = option(
        KotrailConfig.KEY_TEST_ANNOTATIONS, "<fqName,fqName,...>",
        "Comma-separated annotations that mark a function as a test " +
            "(default ${KotrailConfig.DEFAULT_TEST_ANNOTATIONS.joinToString(",")})",
    )
    private val testNamingStyleOption = option(
        KotrailConfig.KEY_TEST_NAMING_STYLE, "<backticked|identifier>",
        "How test functions must be named (default ${KotrailConfig.DEFAULT_TEST_NAMING_STYLE.key})",
    )
    private val testMinNameWordsOption = option(
        KotrailConfig.KEY_TEST_MIN_NAME_WORDS, "<int>",
        "How many words a backticked test name must have " +
            "(default ${KotrailConfig.DEFAULT_TEST_MIN_NAME_WORDS}, 1 accepts any name)",
    )

    private val ruleOptions: List<CliOption> = KotrailRule.switchable.map { rule ->
        option(rule.switchKey, "<true|false>", "Whether the ${rule.key} rule runs (default true)")
    }

    private val noteOptions: List<CliOption> = KotrailRule.entries.map { rule ->
        option(rule.noteKey, "<text>", "Text appended to the ${rule.key} messages, overriding the project-wide note")
    }

    private val severityOptions: List<CliOption> = KotrailRule.entries.map { rule ->
        option(
            rule.severityKey, "<error|warning>",
            "Severity of the ${rule.key} diagnostics (default ${rule.defaultSeverity.name.lowercase()})",
        )
    }

    override val pluginId: String = KotrailNames.PLUGIN_ID
    override val pluginOptions: Collection<CliOption> = listOf(
        configFileOption,
        enabledOption,
        noteOption,
        maxNestingOption,
        trailingLambdaAllowedPackagesOption,
        previewRequireForOption,
        maxComposablesPerFileOption,
        serializationRequiredForOption,
        maxUnusedModelPropertiesOption,
        narrowModelScopeOption,
        referenceFormsOption,
        commentMaxLinesOption,
        kdocMaxLinesOption,
        fqnAllowOption,
        forbiddenFunctionsOption,
        minSameTypeArgumentsOption,
        functionMaxLinesOption,
        composableMaxLinesOption,
        testAnnotationsOption,
        testNamingStyleOption,
        testMinNameWordsOption,
    ) + ruleOptions + severityOptions + noteOptions

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        val name = option.optionName
        KotrailRule.bySwitchKey(name)?.let { rule ->
            configuration.put(KotrailConfigurationKeys.switchKey(rule), KotrailConfig.parseBoolean(name, value))
            return
        }
        KotrailRule.bySeverityKey(name)?.let { rule ->
            configuration.put(KotrailConfigurationKeys.severityKey(rule), KotrailConfig.parseSeverity(name, value))
            return
        }
        KotrailRule.byNoteKey(name)?.let { rule ->
            configuration.put(KotrailConfigurationKeys.noteKey(rule), value)
            return
        }
        when (name) {
            configFileOption.optionName -> configuration.add(KotrailConfigurationKeys.CONFIG_FILE, value)
            enabledOption.optionName -> configuration.put(KotrailConfigurationKeys.ENABLED, KotrailConfig.parseBoolean(name, value))
            noteOption.optionName -> configuration.put(KotrailConfigurationKeys.NOTE, value)
            maxNestingOption.optionName ->
                configuration.put(KotrailConfigurationKeys.COMPOSE_MAX_NESTING, KotrailConfig.parseInt(name, value))
            trailingLambdaAllowedPackagesOption.optionName ->
                configuration.put(KotrailConfigurationKeys.TRAILING_LAMBDA_ALLOWED_PACKAGES, KotrailConfig.parseList(value))
            previewRequireForOption.optionName ->
                configuration.put(KotrailConfigurationKeys.PREVIEW_REQUIRE_FOR, KotrailConfig.parsePreviewScope(name, value))
            maxComposablesPerFileOption.optionName ->
                configuration.put(KotrailConfigurationKeys.MAX_COMPOSABLES_PER_FILE, KotrailConfig.parseInt(name, value))
            serializationRequiredForOption.optionName ->
                configuration.put(KotrailConfigurationKeys.SERIALIZATION_REQUIRED_FOR, KotrailConfig.parseList(value))
            maxUnusedModelPropertiesOption.optionName ->
                configuration.put(KotrailConfigurationKeys.NARROW_MODEL_MAX_UNUSED, KotrailConfig.parseInt(name, value))
            narrowModelScopeOption.optionName ->
                configuration.put(KotrailConfigurationKeys.NARROW_MODEL_SCOPE, KotrailConfig.parseScope(name, value))
            referenceFormsOption.optionName ->
                configuration.put(KotrailConfigurationKeys.REFERENCE_FORMS, KotrailConfig.parseReferenceForms(name, value))
            commentMaxLinesOption.optionName ->
                configuration.put(KotrailConfigurationKeys.COMMENT_MAX_LINES, KotrailConfig.parseInt(name, value))
            kdocMaxLinesOption.optionName ->
                configuration.put(KotrailConfigurationKeys.KDOC_MAX_LINES, KotrailConfig.parseInt(name, value))
            fqnAllowOption.optionName ->
                configuration.put(KotrailConfigurationKeys.FQN_ALLOW, KotrailConfig.parseList(value))
            forbiddenFunctionsOption.optionName ->
                configuration.put(KotrailConfigurationKeys.FORBIDDEN_FUNCTIONS, KotrailConfig.parseList(value))
            minSameTypeArgumentsOption.optionName ->
                configuration.put(KotrailConfigurationKeys.MIN_SAME_TYPE_ARGUMENTS, KotrailConfig.parseInt(name, value))
            functionMaxLinesOption.optionName ->
                configuration.put(KotrailConfigurationKeys.FUNCTION_MAX_LINES, KotrailConfig.parseInt(name, value))
            composableMaxLinesOption.optionName ->
                configuration.put(KotrailConfigurationKeys.COMPOSABLE_MAX_LINES, KotrailConfig.parseInt(name, value))
            testAnnotationsOption.optionName ->
                configuration.put(KotrailConfigurationKeys.TEST_ANNOTATIONS, KotrailConfig.parseList(value))
            testNamingStyleOption.optionName ->
                configuration.put(KotrailConfigurationKeys.TEST_NAMING_STYLE, KotrailConfig.parseTestNamingStyle(name, value))
            testMinNameWordsOption.optionName ->
                configuration.put(KotrailConfigurationKeys.TEST_MIN_NAME_WORDS, KotrailConfig.parseInt(name, value))
            else -> throw CliOptionProcessingException("Unknown option: $name")
        }
    }
}

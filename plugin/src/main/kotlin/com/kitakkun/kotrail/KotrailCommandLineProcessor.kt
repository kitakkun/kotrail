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
    private val excludeOption = option(
        KotrailConfig.KEY_EXCLUDE, "<predicate>",
        "Locations excluded from every rule, e.g. package(com.acme.generated.*) || annotated(com.acme.Generated)",
    )
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
    private val noDataClassScopeOption = option(
        KotrailConfig.KEY_NO_DATA_CLASS_SCOPE, "<explicitApi|all>",
        "Which modules the no-data-class-in-public-API rule applies to " +
            "(default ${KotrailConfig.DEFAULT_NO_DATA_CLASS_SCOPE.key}: only those compiled with explicit API mode)",
    )
    private val visibilityPrivateOption = option(
        KotrailConfig.KEY_VISIBILITY_PRIVATE, "<predicate>",
        "Declarations that must be private, e.g. composable && name(*Preview)",
    )
    private val visibilityInternalOption = option(
        KotrailConfig.KEY_VISIBILITY_INTERNAL, "<predicate>",
        "Declarations that must be internal or private, e.g. name(*Impl)",
    )
    private val requiredAnnotationOption = option(
        "requiredAnnotation", "<name>=<predicate> -> <annotation fqn>",
        "The requiredAnnotation.policy.<name> entry: declarations matching the predicate must carry the " +
            "annotation, e.g. screens=composable && name(*Screen) -> com.acme.Screen. May be given more than " +
            "once; '<name>=' drops the policy",
        allowMultipleOccurrences = true,
    )
    private val knownInsetsOption = option(
        "compose.windowInsets.known", "<composable fqn>=<Type[:Side+Side],...|None>",
        "The compose.windowInsets.known.<fqn> entry: a library composable that handles window insets, " +
            "e.g. com.acme.ui.AppScaffold=SystemBars, or None for one that handles nothing. May be given " +
            "more than once; '<fqn>=' removes the entry",
        allowMultipleOccurrences = true,
    )
    private val localsPlatformOption = option(
        KotrailConfig.KEY_LOCALS_PLATFORM, "<fqName,fqName,...>",
        "Composition locals the platform provides at every root; reads of these are never reported",
    )
    private val localsRequiredOption = option(
        KotrailConfig.KEY_LOCALS_REQUIRED, "<fqName,fqName,...>",
        "Composition locals that must be provided although their default does not throw",
    )
    private val localsRootsOption = option(
        KotrailConfig.KEY_LOCALS_ROOTS, "<fqName,fqName,...>",
        "Functions whose composable lambda is a root of composition " +
            "(default ${KotrailConfig.DEFAULT_LOCALS_ROOTS.joinToString(",")})",
    )
    private val knownLocalsOption = option(
        "compose.compositionLocals.known", "<composable fqn>=<local>, <param>:<local>, ...|None",
        "The compose.compositionLocals.known.<fqn> entry: the locals a library composable reads and the ones " +
            "it provides to each lambda parameter, e.g. com.acme.ui.AppTheme=content:com.acme.ui.LocalPalette. " +
            "May be given more than once; '<fqn>=' removes the entry",
        allowMultipleOccurrences = true,
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

    private val excludeOptions: List<CliOption> = KotrailRule.entries.map { rule ->
        option(rule.excludeKey, "<predicate>", "Locations excluded from the ${rule.key} rule")
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
        excludeOption,
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
        noDataClassScopeOption,
        visibilityPrivateOption,
        visibilityInternalOption,
        requiredAnnotationOption,
        knownInsetsOption,
        localsPlatformOption,
        localsRequiredOption,
        localsRootsOption,
        knownLocalsOption,
        testAnnotationsOption,
        testNamingStyleOption,
        testMinNameWordsOption,
    ) + ruleOptions + severityOptions + noteOptions + excludeOptions

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
        KotrailRule.byExcludeKey(name)?.let { rule ->
            configuration.put(KotrailConfigurationKeys.excludeKey(rule), KotrailConfig.parseExclude(name, value))
            return
        }
        when (name) {
            configFileOption.optionName -> configuration.add(KotrailConfigurationKeys.CONFIG_FILE, value)
            enabledOption.optionName -> configuration.put(KotrailConfigurationKeys.ENABLED, KotrailConfig.parseBoolean(name, value))
            noteOption.optionName -> configuration.put(KotrailConfigurationKeys.NOTE, value)
            excludeOption.optionName -> configuration.put(KotrailConfigurationKeys.EXCLUDE, KotrailConfig.parseExclude(name, value))
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
            noDataClassScopeOption.optionName ->
                configuration.put(KotrailConfigurationKeys.NO_DATA_CLASS_SCOPE, KotrailConfig.parsePublicApiScope(name, value))
            visibilityPrivateOption.optionName ->
                configuration.put(KotrailConfigurationKeys.VISIBILITY_PRIVATE, KotrailConfig.parseExclude(name, value))
            visibilityInternalOption.optionName ->
                configuration.put(KotrailConfigurationKeys.VISIBILITY_INTERNAL, KotrailConfig.parseExclude(name, value))
            requiredAnnotationOption.optionName -> {
                val (policyName, policy) = KotrailConfig.parseRequiredAnnotationOption(value)
                configuration.put(
                    KotrailConfigurationKeys.REQUIRED_ANNOTATIONS,
                    configuration.get(KotrailConfigurationKeys.REQUIRED_ANNOTATIONS).orEmpty() + (policyName to policy),
                )
            }
            knownInsetsOption.optionName -> {
                val (fqn, insets) = KotrailConfig.parseKnownInsetsOption(value)
                configuration.put(
                    KotrailConfigurationKeys.KNOWN_INSETS_HANDLERS,
                    configuration.get(KotrailConfigurationKeys.KNOWN_INSETS_HANDLERS).orEmpty() + (fqn to insets),
                )
            }
            localsPlatformOption.optionName ->
                configuration.put(KotrailConfigurationKeys.LOCALS_PLATFORM, KotrailConfig.parseList(value))
            localsRequiredOption.optionName ->
                configuration.put(KotrailConfigurationKeys.LOCALS_REQUIRED, KotrailConfig.parseList(value))
            localsRootsOption.optionName ->
                configuration.put(KotrailConfigurationKeys.LOCALS_ROOTS, KotrailConfig.parseList(value))
            knownLocalsOption.optionName -> {
                val (fqn, knowledge) = KotrailConfig.parseKnownLocalsOption(value)
                configuration.put(
                    KotrailConfigurationKeys.KNOWN_LOCALS,
                    configuration.get(KotrailConfigurationKeys.KNOWN_LOCALS).orEmpty() + (fqn to knowledge),
                )
            }
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

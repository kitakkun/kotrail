package com.kitakkun.kotrail

import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.diagnostics.Severity
import java.io.File
import java.util.Properties

/** Tunables for the Compose rules. Keys are `compose.<name>`. */
data class KotrailComposeSettings(
    /** Maximum nesting depth of composable calls inside one composable body; 0 disables the rule. */
    val maxNesting: Int,
    /** Packages whose composables may take a callback as a trailing lambda (effect APIs and the like). */
    val trailingLambdaAllowedPackages: List<String>,
    /** Which UI composables must have a `@Preview` in the same file. */
    val previewRequireFor: PreviewScope,
    /** Maximum non-private UI composables (previews excluded) declared in one file; 0 disables the rule. */
    val maxComposablesPerFile: Int,
)

/** Visibilities the preview-required rule inspects. */
enum class PreviewScope(val key: String) {
    PUBLIC("public"),
    INTERNAL("internal"),
    ALL("all");

    companion object {
        fun fromKey(key: String): PreviewScope? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** Which functions the narrow-model-parameters rule inspects. */
enum class NarrowModelParametersScope(val key: String) {
    COMPOSABLES("composables"),
    ALL("all");

    companion object {
        fun fromKey(key: String): NarrowModelParametersScope? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** Tunables for the narrow-model-parameters rule. Keys are `narrowModelParameters.<name>`. */
data class KotrailNarrowModelParameters(
    /** A data-class parameter may leave at most this many properties unread. */
    val maxUnusedProperties: Int,
    val scope: NarrowModelParametersScope,
)

/** The shapes of callable reference the prefer-function-references rule can ask for. */
enum class ReferenceForm(val key: String) {
    /** `::f` for a top-level function or a member on the implicit receiver. */
    TOP_LEVEL("topLevel"),

    /** `value::f`, `this::f`, `Object::f`. */
    BOUND("bound"),

    /** `Type::f` or `Type::property`, the first lambda parameter being the receiver. */
    TYPE_QUALIFIED("typeQualified");

    companion object {
        fun fromKey(key: String): ReferenceForm? = entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
    }
}

/** Tunables for the prefer-function-references rule. Keys are `preferFunctionReferences.<name>`. */
data class KotrailPreferFunctionReferences(
    /** Only lambdas that can become one of these reference forms are reported. */
    val forms: Set<ReferenceForm>,
)

/** Tunables for the comment-length rule. Keys are `comments.<name>`; `0` means unlimited. */
data class KotrailCommentSettings(
    /** Maximum lines for a block comment or a run of consecutive `//` lines. */
    val maxLines: Int,
    /** Maximum lines for a KDoc comment. */
    val maxKDocLines: Int,
)

/** Tunables for the no-FQN-references rule. Keys are `noFqnReferences.<name>`. */
data class KotrailNoFqnReferences(
    /** Package prefixes whose members may be referenced fully qualified. */
    val allow: List<String>,
)

/** Tunables for the forbidden-call rule. Keys are `forbiddenCall.<name>`. */
data class KotrailForbiddenCall(
    /**
     * Fully qualified callables that must not be called, e.g. `kotlin.io.println`,
     * `kotlinx.coroutines.GlobalScope.launch`, `java.lang.Thread.sleep`. Empty means the rule
     * has nothing to report.
     */
    val functions: List<String>,
)

/** Tunables for the must-be-serializable rule. Keys are `serialization.<name>`. */
data class KotrailSerialization(
    /**
     * Fully qualified callables whose type arguments must be serializable with kotlinx.serialization,
     * in addition to anything annotated with `@MustBeSerializable`.
     */
    val requiredFor: List<String>,
)

/** Tunables for the named-arguments rule. Keys are `namedArguments.<name>`. */
data class KotrailNamedArguments(
    /** When at least this many positional arguments share a type, they must be named. */
    val minSameTypeArguments: Int,
)

/**
 * Rule settings resolved once per compilation. Sources, in increasing precedence:
 * built-in defaults, the properties file passed through the `configFile` option, and
 * individual plugin options. Pass a different file (or different options) to test
 * compilations to relax rules there.
 *
 * Rule switches are `rules.<key>` and severities `severity.<key>`, with keys from [KotrailRule].
 */
data class KotrailConfig(
    val enabled: Boolean,
    private val switches: Map<KotrailRule, Boolean>,
    private val severities: Map<KotrailRule, Severity>,
    val compose: KotrailComposeSettings,
    val narrowModelParameters: KotrailNarrowModelParameters,
    val preferFunctionReferences: KotrailPreferFunctionReferences,
    val comments: KotrailCommentSettings,
    val noFqnReferences: KotrailNoFqnReferences,
    val forbiddenCall: KotrailForbiddenCall,
    val namedArguments: KotrailNamedArguments,
    val serialization: KotrailSerialization,
) {
    fun isEnabled(rule: KotrailRule): Boolean = switches[rule] ?: true

    fun severity(rule: KotrailRule): Severity = severities[rule] ?: rule.defaultSeverity

    companion object {
        const val DEFAULT_COMPOSE_MAX_NESTING = 5
        val DEFAULT_TRAILING_LAMBDA_ALLOWED_PACKAGES: List<String> = listOf("androidx.compose.runtime")
        val DEFAULT_PREVIEW_REQUIRE_FOR = PreviewScope.INTERNAL
        const val DEFAULT_MAX_COMPOSABLES_PER_FILE = 3
        val DEFAULT_SERIALIZATION_REQUIRED_FOR: List<String> = listOf("androidx.compose.runtime.saveable.rememberSerializable")
        const val DEFAULT_MAX_UNUSED_MODEL_PROPERTIES = 3
        val DEFAULT_NARROW_MODEL_SCOPE = NarrowModelParametersScope.COMPOSABLES
        val DEFAULT_REFERENCE_FORMS: Set<ReferenceForm> = ReferenceForm.entries.toSet()
        const val DEFAULT_COMMENT_MAX_LINES = 5
        const val DEFAULT_KDOC_MAX_LINES = 0
        val DEFAULT_FQN_ALLOW: List<String> = emptyList()
        val DEFAULT_FORBIDDEN_FUNCTIONS: List<String> = emptyList()
        const val DEFAULT_MIN_SAME_TYPE_ARGUMENTS = 3

        const val KEY_ENABLED = "enabled"
        const val KEY_COMPOSE_MAX_NESTING = "compose.maxNesting"
        const val KEY_TRAILING_LAMBDA_ALLOWED_PACKAGES = "compose.trailingLambdaAllowedPackages"
        const val KEY_PREVIEW_REQUIRE_FOR = "compose.preview.requireFor"
        const val KEY_MAX_COMPOSABLES_PER_FILE = "compose.maxComposablesPerFile"
        const val KEY_SERIALIZATION_REQUIRED_FOR = "serialization.requiredFor"
        const val KEY_NARROW_MODEL_MAX_UNUSED = "narrowModelParameters.maxUnusedProperties"
        const val KEY_NARROW_MODEL_SCOPE = "narrowModelParameters.scope"
        const val KEY_REFERENCE_FORMS = "preferFunctionReferences.forms"
        const val KEY_COMMENT_MAX_LINES = "comments.maxLines"
        const val KEY_KDOC_MAX_LINES = "comments.maxKDocLines"
        const val KEY_FQN_ALLOW = "noFqnReferences.allow"
        const val KEY_FORBIDDEN_FUNCTIONS = "forbiddenCall.functions"
        const val KEY_MIN_SAME_TYPE_ARGUMENTS = "namedArguments.minSameTypeArguments"

        val SETTING_KEYS = listOf(
            KEY_ENABLED,
            KEY_COMPOSE_MAX_NESTING,
            KEY_TRAILING_LAMBDA_ALLOWED_PACKAGES,
            KEY_PREVIEW_REQUIRE_FOR,
            KEY_MAX_COMPOSABLES_PER_FILE,
            KEY_SERIALIZATION_REQUIRED_FOR,
            KEY_NARROW_MODEL_MAX_UNUSED,
            KEY_NARROW_MODEL_SCOPE,
            KEY_REFERENCE_FORMS,
            KEY_COMMENT_MAX_LINES,
            KEY_KDOC_MAX_LINES,
            KEY_FQN_ALLOW,
            KEY_FORBIDDEN_FUNCTIONS,
            KEY_MIN_SAME_TYPE_ARGUMENTS,
        )

        val ALL_KEYS: List<String> =
            SETTING_KEYS + KotrailRule.switchable.map { it.switchKey } + KotrailRule.entries.map { it.severityKey }

        @OptIn(ExperimentalCompilerApi::class)
        fun from(configuration: CompilerConfiguration): KotrailConfig {
            val file = configuration.get(KotrailConfigurationKeys.CONFIG_FILE)?.let(::loadProperties) ?: Properties()

            val switches = KotrailRule.switchable.associateWith { rule ->
                configuration.get(KotrailConfigurationKeys.switchKey(rule))
                    ?: file.boolean(rule.switchKey)
                    ?: true
            }
            val severities = KotrailRule.entries.associateWith { rule ->
                configuration.get(KotrailConfigurationKeys.severityKey(rule))
                    ?: file.getProperty(rule.severityKey)?.let { parseSeverity(rule.severityKey, it) }
                    ?: rule.defaultSeverity
            }

            val maxNesting = configuration.get(KotrailConfigurationKeys.COMPOSE_MAX_NESTING)
                ?: file.int(KEY_COMPOSE_MAX_NESTING)
                ?: DEFAULT_COMPOSE_MAX_NESTING
            val trailingAllowed = configuration.get(KotrailConfigurationKeys.TRAILING_LAMBDA_ALLOWED_PACKAGES)
                ?: file.getProperty(KEY_TRAILING_LAMBDA_ALLOWED_PACKAGES)?.let { parseList(it) }
                ?: DEFAULT_TRAILING_LAMBDA_ALLOWED_PACKAGES
            val previewRequireFor = configuration.get(KotrailConfigurationKeys.PREVIEW_REQUIRE_FOR)
                ?: file.getProperty(KEY_PREVIEW_REQUIRE_FOR)?.let { parsePreviewScope(KEY_PREVIEW_REQUIRE_FOR, it) }
                ?: DEFAULT_PREVIEW_REQUIRE_FOR
            val maxComposablesPerFile = configuration.get(KotrailConfigurationKeys.MAX_COMPOSABLES_PER_FILE)
                ?: file.int(KEY_MAX_COMPOSABLES_PER_FILE)
                ?: DEFAULT_MAX_COMPOSABLES_PER_FILE
            val serializationRequiredFor = configuration.get(KotrailConfigurationKeys.SERIALIZATION_REQUIRED_FOR)
                ?: file.getProperty(KEY_SERIALIZATION_REQUIRED_FOR)?.let { parseList(it) }
                ?: DEFAULT_SERIALIZATION_REQUIRED_FOR
            val maxUnused = configuration.get(KotrailConfigurationKeys.NARROW_MODEL_MAX_UNUSED)
                ?: file.int(KEY_NARROW_MODEL_MAX_UNUSED)
                ?: DEFAULT_MAX_UNUSED_MODEL_PROPERTIES
            val scope = configuration.get(KotrailConfigurationKeys.NARROW_MODEL_SCOPE)
                ?: file.getProperty(KEY_NARROW_MODEL_SCOPE)?.let { parseScope(KEY_NARROW_MODEL_SCOPE, it) }
                ?: DEFAULT_NARROW_MODEL_SCOPE
            val referenceForms = configuration.get(KotrailConfigurationKeys.REFERENCE_FORMS)
                ?: file.getProperty(KEY_REFERENCE_FORMS)?.let { parseReferenceForms(KEY_REFERENCE_FORMS, it) }
                ?: DEFAULT_REFERENCE_FORMS
            val commentMaxLines = configuration.get(KotrailConfigurationKeys.COMMENT_MAX_LINES)
                ?: file.int(KEY_COMMENT_MAX_LINES)
                ?: DEFAULT_COMMENT_MAX_LINES
            val kdocMaxLines = configuration.get(KotrailConfigurationKeys.KDOC_MAX_LINES)
                ?: file.int(KEY_KDOC_MAX_LINES)
                ?: DEFAULT_KDOC_MAX_LINES
            val fqnAllow = configuration.get(KotrailConfigurationKeys.FQN_ALLOW)
                ?: file.getProperty(KEY_FQN_ALLOW)?.let { parseList(it) }
                ?: DEFAULT_FQN_ALLOW
            val forbiddenFunctions = configuration.get(KotrailConfigurationKeys.FORBIDDEN_FUNCTIONS)
                ?: file.getProperty(KEY_FORBIDDEN_FUNCTIONS)?.let { parseList(it) }
                ?: DEFAULT_FORBIDDEN_FUNCTIONS
            val minSameType = configuration.get(KotrailConfigurationKeys.MIN_SAME_TYPE_ARGUMENTS)
                ?: file.int(KEY_MIN_SAME_TYPE_ARGUMENTS)
                ?: DEFAULT_MIN_SAME_TYPE_ARGUMENTS

            return KotrailConfig(
                enabled = configuration.get(KotrailConfigurationKeys.ENABLED) ?: file.boolean(KEY_ENABLED) ?: true,
                switches = switches,
                severities = severities,
                compose = KotrailComposeSettings(
                    maxNesting = maxNesting,
                    trailingLambdaAllowedPackages = trailingAllowed,
                    previewRequireFor = previewRequireFor,
                    maxComposablesPerFile = maxComposablesPerFile,
                ),
                narrowModelParameters = KotrailNarrowModelParameters(maxUnusedProperties = maxUnused, scope = scope),
                preferFunctionReferences = KotrailPreferFunctionReferences(forms = referenceForms),
                comments = KotrailCommentSettings(maxLines = commentMaxLines, maxKDocLines = kdocMaxLines),
                noFqnReferences = KotrailNoFqnReferences(allow = fqnAllow),
                forbiddenCall = KotrailForbiddenCall(functions = forbiddenFunctions),
                namedArguments = KotrailNamedArguments(minSameTypeArguments = minSameType),
                serialization = KotrailSerialization(requiredFor = serializationRequiredFor),
            )
        }

        /** Comma-separated values, trimmed, empties dropped. */
        fun parseList(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        @OptIn(ExperimentalCompilerApi::class)
        fun parseReferenceForms(key: String, value: String): Set<ReferenceForm> {
            val forms = parseList(value).map { entry ->
                ReferenceForm.fromKey(entry) ?: throw CliOptionProcessingException(
                    "Kotrail config key $key accepts a comma-separated subset of " +
                        "${ReferenceForm.entries.joinToString { it.key }}, got '$entry'",
                )
            }.toSet()
            if (forms.isEmpty()) throw CliOptionProcessingException("Kotrail config key $key must list at least one form")
            return forms
        }

        @OptIn(ExperimentalCompilerApi::class)
        private fun loadProperties(path: String): Properties {
            val file = File(path)
            if (!file.isFile) throw CliOptionProcessingException("Kotrail config file not found: $path")
            val properties = Properties()
            file.reader().use(properties::load)
            val unknown = properties.stringPropertyNames() - ALL_KEYS.toSet()
            if (unknown.isNotEmpty()) {
                throw CliOptionProcessingException("Unknown keys in Kotrail config file $path: ${unknown.sorted().joinToString()}")
            }
            return properties
        }

        @OptIn(ExperimentalCompilerApi::class)
        private fun Properties.int(key: String): Int? = getProperty(key)?.trim()?.let { parseInt(key, it) }

        @OptIn(ExperimentalCompilerApi::class)
        private fun Properties.boolean(key: String): Boolean? = getProperty(key)?.trim()?.let { parseBoolean(key, it) }

        @OptIn(ExperimentalCompilerApi::class)
        fun parseInt(key: String, value: String): Int =
            value.trim().toIntOrNull() ?: throw CliOptionProcessingException("Kotrail config key $key must be an integer, got '$value'")

        @OptIn(ExperimentalCompilerApi::class)
        fun parseBoolean(key: String, value: String): Boolean = when (value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw CliOptionProcessingException("Kotrail config key $key must be true or false, got '$value'")
        }

        @OptIn(ExperimentalCompilerApi::class)
        fun parseSeverity(key: String, value: String): Severity = when (value.trim().lowercase()) {
            "error" -> Severity.ERROR
            "warning" -> Severity.WARNING
            else -> throw CliOptionProcessingException("Kotrail config key $key must be error or warning, got '$value'")
        }

        @OptIn(ExperimentalCompilerApi::class)
        fun parsePreviewScope(key: String, value: String): PreviewScope =
            PreviewScope.fromKey(value) ?: throw CliOptionProcessingException(
                "Kotrail config key $key must be one of ${PreviewScope.entries.joinToString { it.key }}, got '$value'",
            )

        @OptIn(ExperimentalCompilerApi::class)
        fun parseScope(key: String, value: String): NarrowModelParametersScope =
            NarrowModelParametersScope.fromKey(value) ?: throw CliOptionProcessingException(
                "Kotrail config key $key must be one of ${NarrowModelParametersScope.entries.joinToString { it.key }}, got '$value'",
            )
    }
}

package com.kitakkun.kotrail

import com.kitakkun.kotrail.compose.insets.InsetsSet
import com.kitakkun.kotrail.compose.insets.Sides
import com.kitakkun.kotrail.exclude.ExcludeParser
import com.kitakkun.kotrail.exclude.ExcludePredicate
import com.kitakkun.kotrail.exclude.ReportSite
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.diagnostics.Severity
import java.io.File
import java.util.Properties

/** Tunables for the Compose rules. Keys are `compose.<rule>.<name>`. */
data class KotrailComposeSettings(
    /** Maximum nesting depth of composable calls inside one composable body; 0 disables the rule. */
    val maxNesting: Int,
    /** Packages whose composables may take a callback as a trailing lambda (effect APIs and the like). */
    val trailingLambdaAllowedPackages: List<String>,
    /** Which UI composables must have a `@Preview` in the same file. */
    val previewRequireFor: PreviewScope,
    /** Maximum non-private UI composables (previews excluded) declared in one file; 0 disables the rule. */
    val maxComposablesPerFile: Int,
    /**
     * The project's changes to the insets knowledge base, keyed by the composable's fully
     * qualified name, from the `compose.windowInsets.known[<fqn>]` entries. A set replaces the
     * built-in entry (an empty set, written `None`, says the composable handles nothing); `null`
     * removes an earlier override so that the built-in entry applies again.
     */
    val knownInsetsHandlers: Map<String, InsetsSet?>,
    val compositionLocals: KotrailCompositionLocals,
)

/** What the knowledge base says about one library composable: the locals it reads, and those it provides to each lambda parameter. */
data class KotrailCompositionLocalKnowledge(
    val reads: Set<String>,
    val provides: Map<String, Set<String>>,
)

/** Tunables for the composition-locals rule. Keys are `compose.compositionLocals.<name>`. */
data class KotrailCompositionLocals(
    /** Locals the platform provides at every root (fully qualified property names); reads of these are never reported. */
    val platform: List<String>,
    /** Locals that must be provided even though their default does not throw. */
    val required: List<String>,
    /** Functions whose composable lambda argument is a root of composition: `setContent`, `Window`, ... */
    val roots: List<String>,
    /**
     * Library composables described by `compose.compositionLocals.known[<fqn>]`; `null` removes
     * an earlier entry so that the composable is analyzed like any other.
     */
    val known: Map<String, KotrailCompositionLocalKnowledge?>,
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

/** Tunables for the comment-length rule. Keys are `commentLength.<name>`; `0` means unlimited. */
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

/** Tunables for the must-be-serializable rule. Keys are `mustBeSerializable.<name>`. */
data class KotrailSerialization(
    /**
     * Fully qualified callables whose type arguments must be serializable with kotlinx.serialization,
     * in addition to anything annotated with `@MustBeSerializable`.
     */
    val requiredFor: List<String>,
)

/**
 * Locations excluded from rules: the project-wide `exclude` predicate and one per rule under
 * `exclude.<rule>`. A diagnostic is dropped when either matches where it would be reported.
 */
class KotrailExcludes(
    private val everywhere: ExcludePredicate?,
    private val perRule: Map<KotrailRule, ExcludePredicate>,
) {
    /** Whether any predicate applies to [rule], so that the site is only described when needed. */
    fun isConfiguredFor(rule: KotrailRule): Boolean = everywhere != null || rule in perRule

    fun matches(rule: KotrailRule, site: ReportSite): Boolean =
        everywhere?.matches(site) == true || perRule[rule]?.matches(site) == true

    companion object {
        val NONE = KotrailExcludes(everywhere = null, perRule = emptyMap())
    }
}

/**
 * Tunables for the visibility-policy rule. Keys are `visibilityPolicy.<name>`. Each holds a
 * predicate over declarations (the same language as `exclude`); a matching declaration must be
 * at most that visible.
 */
data class KotrailVisibilityPolicy(
    /** Declarations that must be `private`. */
    val private: ExcludePredicate?,
    /** Declarations that must be `internal` or `private`. */
    val internal: ExcludePredicate?,
) {
    val isEmpty: Boolean get() = private == null && internal == null
}

/**
 * One policy of the required-annotation rule: declarations matching [predicate] must carry
 * [annotation]. [name] is what the policy was configured under (`requiredAnnotation.policy[<name>]`)
 * and appears in the message.
 */
data class KotrailRequiredAnnotation(
    val name: String,
    val predicate: ExcludePredicate,
    /** Fully qualified annotation class name. */
    val annotation: String,
)

/** Which modules a library-facing rule applies to. */
enum class PublicApiScope(val key: String) {
    /** Only modules compiled with explicit API mode, which is how a library declares itself. */
    EXPLICIT_API("explicitApi"),
    ALL("all");

    companion object {
        fun fromKey(key: String): PublicApiScope? = entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
    }
}

/** Tunables for the no-data-class-in-public-API rule. Keys are `noDataClassInPublicApi.<name>`. */
data class KotrailNoDataClassInPublicApi(
    val scope: PublicApiScope,
)

/** Tunables for the function-length rule. Keys are `functionLength.<name>`; `0` means unlimited. */
data class KotrailFunctionLength(
    /** Most lines of code a function body may have. */
    val maxLines: Int,
    /** Most lines of code a `@Composable` function body may have; UI trees run longer than logic. */
    val maxComposableLines: Int,
)

/** Tunables for the named-arguments rule. Keys are `namedArgumentsForRepeatedTypes.<name>`. */
data class KotrailNamedArguments(
    /** When at least this many positional arguments share a type, they must be named. */
    val minSameTypeArguments: Int,
)

/** How a test function must be named. */
enum class TestNamingStyle(val key: String) {
    /** A backticked sentence, which is what Kotlin's own conventions allow in tests. */
    BACKTICKED("backticked"),

    /** A plain identifier, for targets that reject method names with spaces (Android instrumented tests). */
    IDENTIFIER("identifier");

    companion object {
        fun fromKey(key: String): TestNamingStyle? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** Tunables for the test rules. Keys are `test.<name>`. */
data class KotrailTest(
    /**
     * Fully qualified annotations that mark a function as a test. Replacing the list is how a
     * project teaches Kotrail about its own framework; an empty list stops the test rules from
     * recognizing anything.
     */
    val annotations: List<String>,
    val namingStyle: TestNamingStyle,
    /** A backticked test name must have at least this many words; 1 accepts any name. */
    val minNameWords: Int,
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
    private val notes: Map<KotrailRule, String>,
    val excludes: KotrailExcludes,
    val compose: KotrailComposeSettings,
    val narrowModelParameters: KotrailNarrowModelParameters,
    val preferFunctionReferences: KotrailPreferFunctionReferences,
    val comments: KotrailCommentSettings,
    val noFqnReferences: KotrailNoFqnReferences,
    val forbiddenCall: KotrailForbiddenCall,
    val namedArguments: KotrailNamedArguments,
    val serialization: KotrailSerialization,
    val test: KotrailTest,
    val functionLength: KotrailFunctionLength,
    val noDataClassInPublicApi: KotrailNoDataClassInPublicApi,
    val visibilityPolicy: KotrailVisibilityPolicy,
    /** The `requiredAnnotation.policy[<name>]` entries, by name. */
    val requiredAnnotations: List<KotrailRequiredAnnotation>,
) {
    fun isEnabled(rule: KotrailRule): Boolean = switches[rule] ?: true

    fun severity(rule: KotrailRule): Severity = severities[rule] ?: rule.defaultSeverity

    /**
     * The project's own text for this rule, appended to the built-in message and already prefixed
     * with a space, or empty. A rule's own `note.<key>` wins over the project-wide `note`.
     *
     * The note is added to the message the rule always reports, never substituted for it, so the
     * rewrite a rule asks for cannot be lost by configuring one.
     */
    fun note(rule: KotrailRule): String = notes[rule].orEmpty()

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
        const val DEFAULT_FUNCTION_MAX_LINES = 50
        val DEFAULT_NO_DATA_CLASS_SCOPE = PublicApiScope.EXPLICIT_API
        const val DEFAULT_COMPOSABLE_MAX_LINES = 80
        val DEFAULT_TEST_ANNOTATIONS: List<String> = listOf(
            "kotlin.test.Test",
            "org.junit.Test",
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.api.TestFactory",
            "org.junit.jupiter.api.TestTemplate",
            "org.junit.jupiter.params.ParameterizedTest",
        )
        val DEFAULT_TEST_NAMING_STYLE = TestNamingStyle.BACKTICKED
        const val DEFAULT_TEST_MIN_NAME_WORDS = 3

        const val KEY_ENABLED = "enabled"
        const val KEY_NOTE = "note"
        const val KEY_EXCLUDE = "exclude"
        const val KEY_COMPOSE_MAX_NESTING = "compose.nesting.maxDepth"
        const val KEY_TRAILING_LAMBDA_ALLOWED_PACKAGES = "compose.noTrailingCallback.allowedPackages"
        const val KEY_PREVIEW_REQUIRE_FOR = "compose.previewRequired.scope"
        const val KEY_MAX_COMPOSABLES_PER_FILE = "compose.composablesPerFile.max"
        const val KEY_SERIALIZATION_REQUIRED_FOR = "mustBeSerializable.requiredFor"
        const val KEY_NARROW_MODEL_MAX_UNUSED = "narrowModelParameters.maxUnusedProperties"
        const val KEY_NARROW_MODEL_SCOPE = "narrowModelParameters.scope"
        const val KEY_REFERENCE_FORMS = "preferFunctionReferences.forms"
        const val KEY_COMMENT_MAX_LINES = "commentLength.maxLines"
        const val KEY_KDOC_MAX_LINES = "commentLength.maxKDocLines"
        const val KEY_FQN_ALLOW = "noFqnReferences.allow"
        const val KEY_FORBIDDEN_FUNCTIONS = "forbiddenCall.functions"
        const val KEY_MIN_SAME_TYPE_ARGUMENTS = "namedArgumentsForRepeatedTypes.minArguments"
        const val KEY_FUNCTION_MAX_LINES = "functionLength.maxLines"
        const val KEY_NO_DATA_CLASS_SCOPE = "noDataClassInPublicApi.scope"
        const val KEY_VISIBILITY_PRIVATE = "visibilityPolicy.private"
        const val KEY_VISIBILITY_INTERNAL = "visibilityPolicy.internal"
        const val KEY_COMPOSABLE_MAX_LINES = "functionLength.maxComposableLines"
        const val KEY_TEST_ANNOTATIONS = "test.annotations"
        const val KEY_TEST_NAMING_STYLE = "test.naming.style"
        const val KEY_TEST_MIN_NAME_WORDS = "test.naming.minWords"
        const val KEY_LOCALS_PLATFORM = "compose.compositionLocals.platform"
        const val KEY_LOCALS_REQUIRED = "compose.compositionLocals.required"
        const val KEY_LOCALS_ROOTS = "compose.compositionLocals.roots"

        /**
         * Family of `requiredAnnotation.policy[<name>]=<predicate> -> <annotation fqn>` entries.
         * A user-chosen key sits in brackets, so that it can hold dots (a fully qualified name)
         * and still leave `<family>.<setting>` and `<family>[<key>].<setting>` parseable.
         */
        const val KEY_FAMILY_REQUIRED_ANNOTATION = "requiredAnnotation.policy"

        /** Family of `compose.windowInsets.known[<composable fqn>]=<Type[:Sides],...|None>` entries. */
        const val KEY_FAMILY_KNOWN_INSETS = "compose.windowInsets.known"

        /** Family of `compose.compositionLocals.known[<composable fqn>]=<local>, <param>:<local>, ...|None` entries. */
        const val KEY_FAMILY_KNOWN_LOCALS = "compose.compositionLocals.known"

        /** Key families whose bracketed key is chosen by the project, accepted by the config file next to [ALL_KEYS]. */
        val KEY_FAMILIES: List<String> = listOf(KEY_FAMILY_REQUIRED_ANNOTATION, KEY_FAMILY_KNOWN_INSETS, KEY_FAMILY_KNOWN_LOCALS)

        /** What a user-chosen key may look like: letters, digits, dots, `_` and `-`, starting with a letter. */
        val ENTRY_NAME = Regex("[A-Za-z][A-Za-z0-9_.-]*")

        val DEFAULT_LOCALS_PLATFORM: List<String> = emptyList()
        val DEFAULT_LOCALS_REQUIRED: List<String> = emptyList()
        val DEFAULT_LOCALS_ROOTS: List<String> = listOf(
            "androidx.activity.compose.setContent",
            "androidx.compose.ui.window.Window",
            "androidx.compose.ui.window.application",
            "androidx.compose.ui.window.singleWindowApplication",
            "androidx.compose.ui.window.ComposeUIViewController",
            "androidx.compose.ui.window.ComposeViewport",
            "androidx.compose.ui.window.CanvasBasedWindow",
        )

        val SETTING_KEYS = listOf(
            KEY_ENABLED,
            KEY_NOTE,
            KEY_EXCLUDE,
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
            KEY_FUNCTION_MAX_LINES,
            KEY_COMPOSABLE_MAX_LINES,
            KEY_NO_DATA_CLASS_SCOPE,
            KEY_VISIBILITY_PRIVATE,
            KEY_VISIBILITY_INTERNAL,
            KEY_TEST_ANNOTATIONS,
            KEY_TEST_NAMING_STYLE,
            KEY_TEST_MIN_NAME_WORDS,
            KEY_LOCALS_PLATFORM,
            KEY_LOCALS_REQUIRED,
            KEY_LOCALS_ROOTS,
        )

        val ALL_KEYS: List<String> = SETTING_KEYS +
            KotrailRule.switchable.map { it.switchKey } +
            KotrailRule.entries.map { it.severityKey } +
            KotrailRule.entries.map { it.noteKey } +
            KotrailRule.entries.map { it.excludeKey }

        @OptIn(ExperimentalCompilerApi::class)
        fun from(configuration: CompilerConfiguration): KotrailConfig {
            val file = loadProperties(configuration.get(KotrailConfigurationKeys.CONFIG_FILE).orEmpty())

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

            val projectNote = configuration.get(KotrailConfigurationKeys.NOTE) ?: file.getProperty(KEY_NOTE)
            val notes = KotrailRule.entries.associateWith { rule ->
                val text = configuration.get(KotrailConfigurationKeys.noteKey(rule))
                    ?: file.getProperty(rule.noteKey)
                    ?: projectNote
                if (text.isNullOrBlank()) "" else " " + text.trim()
            }

            // An empty predicate (a later file or an option that clears one set earlier) parses to
            // Never and is dropped here, so that "unset" is spelled the same way in every layer.
            val excludes = KotrailExcludes(
                everywhere = (
                    configuration.get(KotrailConfigurationKeys.EXCLUDE)
                        ?: file.getProperty(KEY_EXCLUDE)?.let { parseExclude(KEY_EXCLUDE, it) }
                    ).unlessNever(),
                perRule = KotrailRule.entries.mapNotNull { rule ->
                    val predicate = (
                        configuration.get(KotrailConfigurationKeys.excludeKey(rule))
                            ?: file.getProperty(rule.excludeKey)?.let { parseExclude(rule.excludeKey, it) }
                        ).unlessNever()
                    predicate?.let { rule to it }
                }.toMap(),
            )

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

            val functionMaxLines = configuration.get(KotrailConfigurationKeys.FUNCTION_MAX_LINES)
                ?: file.int(KEY_FUNCTION_MAX_LINES)
                ?: DEFAULT_FUNCTION_MAX_LINES
            val composableMaxLines = configuration.get(KotrailConfigurationKeys.COMPOSABLE_MAX_LINES)
                ?: file.int(KEY_COMPOSABLE_MAX_LINES)
                ?: DEFAULT_COMPOSABLE_MAX_LINES
            val noDataClassScope = configuration.get(KotrailConfigurationKeys.NO_DATA_CLASS_SCOPE)
                ?: file.getProperty(KEY_NO_DATA_CLASS_SCOPE)?.let { parsePublicApiScope(KEY_NO_DATA_CLASS_SCOPE, it) }
                ?: DEFAULT_NO_DATA_CLASS_SCOPE
            val visibilityPolicy = KotrailVisibilityPolicy(
                private = (
                    configuration.get(KotrailConfigurationKeys.VISIBILITY_PRIVATE)
                        ?: file.getProperty(KEY_VISIBILITY_PRIVATE)?.let { parseExclude(KEY_VISIBILITY_PRIVATE, it) }
                    ).unlessNever(),
                internal = (
                    configuration.get(KotrailConfigurationKeys.VISIBILITY_INTERNAL)
                        ?: file.getProperty(KEY_VISIBILITY_INTERNAL)?.let { parseExclude(KEY_VISIBILITY_INTERNAL, it) }
                    ).unlessNever(),
            )
            // Named entries: later files and options override the entry of the same name, and every
            // other entry stays, so a test source set can add or drop one policy without restating
            // them all. An empty value is the way to drop one (it parses to null).
            val requiredAnnotations = (
                file.entriesOf(KEY_FAMILY_REQUIRED_ANNOTATION)
                    .associate { (name, value) -> name to parseRequiredAnnotation(name, value) } +
                    configuration.get(KotrailConfigurationKeys.REQUIRED_ANNOTATIONS).orEmpty()
                ).values.filterNotNull()
            val knownInsetsHandlers =
                file.entriesOf(KEY_FAMILY_KNOWN_INSETS)
                    .associate { (fqn, value) -> fqn to parseInsetsSpec(entryKey(KEY_FAMILY_KNOWN_INSETS, fqn), value) } +
                    configuration.get(KotrailConfigurationKeys.KNOWN_INSETS_HANDLERS).orEmpty()
            val compositionLocals = KotrailCompositionLocals(
                platform = configuration.get(KotrailConfigurationKeys.LOCALS_PLATFORM)
                    ?: file.getProperty(KEY_LOCALS_PLATFORM)?.let { parseList(it) }
                    ?: DEFAULT_LOCALS_PLATFORM,
                required = configuration.get(KotrailConfigurationKeys.LOCALS_REQUIRED)
                    ?: file.getProperty(KEY_LOCALS_REQUIRED)?.let { parseList(it) }
                    ?: DEFAULT_LOCALS_REQUIRED,
                roots = configuration.get(KotrailConfigurationKeys.LOCALS_ROOTS)
                    ?: file.getProperty(KEY_LOCALS_ROOTS)?.let { parseList(it) }
                    ?: DEFAULT_LOCALS_ROOTS,
                known = file.entriesOf(KEY_FAMILY_KNOWN_LOCALS)
                    .associate { (fqn, value) -> fqn to parseLocalsSpec(entryKey(KEY_FAMILY_KNOWN_LOCALS, fqn), value) } +
                    configuration.get(KotrailConfigurationKeys.KNOWN_LOCALS).orEmpty(),
            )
            val testAnnotations = configuration.get(KotrailConfigurationKeys.TEST_ANNOTATIONS)
                ?: file.getProperty(KEY_TEST_ANNOTATIONS)?.let { parseList(it) }
                ?: DEFAULT_TEST_ANNOTATIONS
            val testNamingStyle = configuration.get(KotrailConfigurationKeys.TEST_NAMING_STYLE)
                ?: file.getProperty(KEY_TEST_NAMING_STYLE)?.let { parseTestNamingStyle(KEY_TEST_NAMING_STYLE, it) }
                ?: DEFAULT_TEST_NAMING_STYLE
            val testMinNameWords = configuration.get(KotrailConfigurationKeys.TEST_MIN_NAME_WORDS)
                ?: file.int(KEY_TEST_MIN_NAME_WORDS)
                ?: DEFAULT_TEST_MIN_NAME_WORDS

            return KotrailConfig(
                enabled = configuration.get(KotrailConfigurationKeys.ENABLED) ?: file.boolean(KEY_ENABLED) ?: true,
                switches = switches,
                severities = severities,
                notes = notes,
                excludes = excludes,
                compose = KotrailComposeSettings(
                    maxNesting = maxNesting,
                    trailingLambdaAllowedPackages = trailingAllowed,
                    previewRequireFor = previewRequireFor,
                    maxComposablesPerFile = maxComposablesPerFile,
                    knownInsetsHandlers = knownInsetsHandlers,
                    compositionLocals = compositionLocals,
                ),
                narrowModelParameters = KotrailNarrowModelParameters(maxUnusedProperties = maxUnused, scope = scope),
                preferFunctionReferences = KotrailPreferFunctionReferences(forms = referenceForms),
                comments = KotrailCommentSettings(maxLines = commentMaxLines, maxKDocLines = kdocMaxLines),
                noFqnReferences = KotrailNoFqnReferences(allow = fqnAllow),
                forbiddenCall = KotrailForbiddenCall(functions = forbiddenFunctions),
                namedArguments = KotrailNamedArguments(minSameTypeArguments = minSameType),
                serialization = KotrailSerialization(requiredFor = serializationRequiredFor),
                test = KotrailTest(
                    annotations = testAnnotations,
                    namingStyle = testNamingStyle,
                    minNameWords = testMinNameWords,
                ),
                functionLength = KotrailFunctionLength(
                    maxLines = functionMaxLines,
                    maxComposableLines = composableMaxLines,
                ),
                noDataClassInPublicApi = KotrailNoDataClassInPublicApi(scope = noDataClassScope),
                visibilityPolicy = visibilityPolicy,
                requiredAnnotations = requiredAnnotations,
            )
        }

        /**
         * A `requiredAnnotation.policy[<name>]` value: a predicate, `->`, and the annotation's fully
         * qualified name. An empty value drops the policy of that name and gives `null`.
         */
        @OptIn(ExperimentalCompilerApi::class)
        fun parseRequiredAnnotation(name: String, value: String): KotrailRequiredAnnotation? {
            val key = entryKey(KEY_FAMILY_REQUIRED_ANNOTATION, name)
            if (!ENTRY_NAME.matches(name)) {
                throw CliOptionProcessingException(
                    "Kotrail config key $key: a policy name is a letter followed by letters, digits, '.', '_' or '-', got '$name'",
                )
            }
            if (value.isBlank()) return null
            val arrow = value.lastIndexOf("->")
            if (arrow < 0) {
                throw CliOptionProcessingException(
                    "Kotrail config key $key must be '<predicate> -> <annotation fqn>', got '$value'",
                )
            }
            val annotation = value.substring(arrow + 2).trim()
            if (annotation.isEmpty() || annotation.any { it.isWhitespace() }) {
                throw CliOptionProcessingException("Kotrail config key $key must end with one fully qualified annotation, got '$value'")
            }
            return KotrailRequiredAnnotation(
                name = name.trim(),
                predicate = parseExclude(key, value.substring(0, arrow)),
                annotation = annotation.removePrefix("@"),
            )
        }

        /**
         * A `requiredAnnotation` option value, `<name>=<predicate> -> <annotation fqn>`, which is
         * the file entry with its key prefix dropped; `<name>=` drops the policy.
         */
        @OptIn(ExperimentalCompilerApi::class)
        fun parseRequiredAnnotationOption(value: String): Pair<String, KotrailRequiredAnnotation?> {
            val parts = value.split('=', limit = 2)
            if (parts.size != 2) {
                throw CliOptionProcessingException(
                    "Kotrail option requiredAnnotation must be '<name>=<predicate> -> <annotation fqn>', got '$value'",
                )
            }
            val name = parts[0].trim()
            return name to parseRequiredAnnotation(name, parts[1])
        }

        /**
         * An insets specification: comma-separated `Type` or `Type:Side+Side` entries, with the
         * entry names of `WindowInsetsType` (`SystemBars`, `Ime`, ...) and of `WindowInsetsSide`
         * (`Top`, `Horizontal`, ...), or `None` for a composable that handles nothing. An empty
         * value removes the entry, so that the built-in knowledge applies, and gives `null`.
         */
        @OptIn(ExperimentalCompilerApi::class)
        fun parseInsetsSpec(key: String, value: String): InsetsSet? {
            if (value.isBlank()) return null
            if (value.trim() == INSETS_NONE) return InsetsSet.EMPTY
            var result = InsetsSet.EMPTY
            for (entry in parseList(value)) {
                val parts = entry.split(':', limit = 2).map { it.trim() }
                if (parts[0].firstOrNull()?.isUpperCase() != true) {
                    throw CliOptionProcessingException(
                        "Kotrail config key $key: '${parts[0]}' in '$entry' is not a WindowInsetsType entry; write it as SystemBars, Ime, ...",
                    )
                }
                val sides = if (parts.size == 2) {
                    parts[1].split('+').map { it.trim() }.fold(Sides.NONE) { acc, side ->
                        acc or (Sides.fromName(side) ?: throw CliOptionProcessingException(
                            "Kotrail config key $key: unknown side '$side' in '$entry'; " +
                                "use Top, Bottom, Left, Right, Start, End, Horizontal, Vertical, or All",
                        ))
                    }
                } else {
                    Sides.ALL
                }
                val insets = InsetsSet.fromTypeName(parts[0], sides) ?: throw CliOptionProcessingException(
                    "Kotrail config key $key: unknown insets type '${parts[0]}' in '$entry'; use a WindowInsetsType name such as SystemBars",
                )
                result = result.union(insets)
            }
            return result
        }

        /** The spec of a composable that handles no insets at all, or reads and provides no locals. */
        const val INSETS_NONE = "None"

        /**
         * A composition-locals specification: comma-separated entries, each a fully qualified
         * local the composable reads, or `<parameter>:<local>` for a local it provides to that
         * lambda parameter; `None` for a composable that does neither. An empty value removes
         * the entry and gives `null`.
         */
        @OptIn(ExperimentalCompilerApi::class)
        fun parseLocalsSpec(key: String, value: String): KotrailCompositionLocalKnowledge? {
            if (value.isBlank()) return null
            if (value.trim() == INSETS_NONE) return KotrailCompositionLocalKnowledge(emptySet(), emptyMap())
            val reads = LinkedHashSet<String>()
            val provides = LinkedHashMap<String, MutableSet<String>>()
            for (entry in parseList(value)) {
                val parts = entry.split(':', limit = 2).map { it.trim() }
                if (parts.any { it.isEmpty() || it.any(Char::isWhitespace) }) {
                    throw CliOptionProcessingException(
                        "Kotrail config key $key: '$entry' is not a fully qualified local or '<parameter>:<local>'",
                    )
                }
                if (parts.size == 2) provides.getOrPut(parts[0]) { LinkedHashSet() } += parts[1] else reads += parts[0]
            }
            return KotrailCompositionLocalKnowledge(reads, provides)
        }

        /** A `compose.compositionLocals.known` option value, `<composable fqn>=<spec>`; `<fqn>=` removes the entry. */
        @OptIn(ExperimentalCompilerApi::class)
        fun parseKnownLocalsOption(value: String): Pair<String, KotrailCompositionLocalKnowledge?> {
            val parts = value.split('=', limit = 2)
            if (parts.size != 2 || parts[0].isBlank()) {
                throw CliOptionProcessingException(
                    "Kotrail option compose.compositionLocals.known must be '<composable fqn>=<local>, <param>:<local>, ...|None', got '$value'",
                )
            }
            val fqn = parts[0].trim()
            return fqn to parseLocalsSpec(entryKey(KEY_FAMILY_KNOWN_LOCALS, fqn), parts[1])
        }

        /** A `compose.windowInsets.known` option value, `<composable fqn>=<spec>`; `<fqn>=` removes the entry. */
        @OptIn(ExperimentalCompilerApi::class)
        fun parseKnownInsetsOption(value: String): Pair<String, InsetsSet?> {
            val parts = value.split('=', limit = 2)
            if (parts.size != 2 || parts[0].isBlank()) {
                throw CliOptionProcessingException(
                    "Kotrail option compose.windowInsets.known must be '<composable fqn>=<Type[:Sides],...>', got '$value'",
                )
            }
            val fqn = parts[0].trim()
            return fqn to parseInsetsSpec(entryKey(KEY_FAMILY_KNOWN_INSETS, fqn), parts[1])
        }

        /** A predicate as configured, or `null` when it was cleared with an empty value. */
        private fun ExcludePredicate?.unlessNever(): ExcludePredicate? = takeUnless { it is ExcludePredicate.Never }

        /** The key of one entry of a family: `family[name]`. */
        fun entryKey(family: String, name: String): String = "$family[$name]"

        private fun entryPattern(family: String) = Regex("^" + Regex.escape(family) + """\[([^\]]+)]$""")

        /** `(name, value)` for every `family[name]` property, sorted by key. */
        private fun Properties.entriesOf(family: String): List<Pair<String, String>> {
            val pattern = entryPattern(family)
            return stringPropertyNames().mapNotNull { key -> pattern.matchEntire(key)?.let { it.groupValues[1] to getProperty(key) } }
                .sortedBy { it.first }
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

        /** The files merged in order, so that a later file overrides the entries of an earlier one. */
        @OptIn(ExperimentalCompilerApi::class)
        private fun loadProperties(paths: List<String>): Properties {
            val merged = Properties()
            for (path in paths) {
                val file = File(path)
                if (!file.isFile) throw CliOptionProcessingException("Kotrail config file not found: $path")
                val properties = Properties()
                file.reader().use(properties::load)
                val unknown = properties.stringPropertyNames()
                    .filter { key -> key !in ALL_KEYS && KEY_FAMILIES.none { entryPattern(it).matches(key) } }
                if (unknown.isNotEmpty()) {
                    throw CliOptionProcessingException(
                        "Unknown keys in Kotrail config file $path: ${unknown.sorted().joinToString()}",
                    )
                }
                merged.putAll(properties)
            }
            return merged
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

        /** An empty value gives [ExcludePredicate.Never], which the loader treats as "unset". */
        @OptIn(ExperimentalCompilerApi::class)
        fun parseExclude(key: String, value: String): ExcludePredicate = try {
            if (value.isBlank()) ExcludePredicate.Never else ExcludeParser.parse(value)
        } catch (e: ExcludeParser.ExcludeSyntaxException) {
            throw CliOptionProcessingException("Kotrail config key $key: ${e.message}")
        }

        @OptIn(ExperimentalCompilerApi::class)
        fun parsePublicApiScope(key: String, value: String): PublicApiScope =
            PublicApiScope.fromKey(value) ?: throw CliOptionProcessingException(
                "Kotrail config key $key must be one of ${PublicApiScope.entries.joinToString { it.key }}, got '$value'",
            )

        @OptIn(ExperimentalCompilerApi::class)
        fun parseTestNamingStyle(key: String, value: String): TestNamingStyle =
            TestNamingStyle.fromKey(value) ?: throw CliOptionProcessingException(
                "Kotrail config key $key must be one of ${TestNamingStyle.entries.joinToString { it.key }}, got '$value'",
            )

        @OptIn(ExperimentalCompilerApi::class)
        fun parseScope(key: String, value: String): NarrowModelParametersScope =
            NarrowModelParametersScope.fromKey(value) ?: throw CliOptionProcessingException(
                "Kotrail config key $key must be one of ${NarrowModelParametersScope.entries.joinToString { it.key }}, got '$value'",
            )
    }
}

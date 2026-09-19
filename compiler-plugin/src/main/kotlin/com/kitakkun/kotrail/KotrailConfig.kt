package com.kitakkun.kotrail

import com.kitakkun.kotrail.compose.insets.InsetsSet
import com.kitakkun.kotrail.compose.insets.Sides
import com.kitakkun.kotrail.exclude.CallPredicate
import com.kitakkun.kotrail.exclude.CallPredicateParser
import com.kitakkun.kotrail.exclude.ExcludeParser
import com.kitakkun.kotrail.exclude.Glob
import com.kitakkun.kotrail.exclude.ExcludePredicate
import com.kitakkun.kotrail.exclude.ReportSite
import com.kitakkun.kotrail.config.ConfigException
import com.kitakkun.kotrail.config.ConfigNode
import com.kitakkun.kotrail.config.ConfigSchema
import com.kitakkun.kotrail.config.ConfigTree
import com.kitakkun.kotrail.config.KotrailYaml
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.diagnostics.Severity
import java.io.File

/** Tunables for the Compose rules, from the `rules.compose.<rule>` mappings. */
data class KotrailComposeSettings(
    /** Maximum nesting depth of composable calls inside one composable body; 0 disables the rule. */
    val maxNesting: Int,
    /** Packages whose composables may take a callback as a trailing lambda (effect APIs and the like). */
    val trailingLambdaAllowedPackages: List<String>,
    /** Which UI composables must have a `@Preview` in the same file. */
    val previewRequireFor: PreviewScope,
    /** Maximum non-private UI composables (previews excluded) declared in one file; 0 disables the rule. */
    val maxComposablesPerFile: Int,
    /** Whether overloads of one composable name count one each toward that limit, rather than as one component. */
    val countOverloadsSeparately: Boolean,
    /** Return types (fully qualified) whose producers start work when called in a composable body: `Job`, `Deferred`. */
    val sideEffectTypes: List<String>,
    /** Functions (fully qualified) that start work when called in a composable body, in addition to those recognized by type. */
    val sideEffectFunctions: List<String>,
    /** Names of composable parameters that must not receive a string literal. */
    val hardcodedStringParameters: List<String>,
    /**
     * The project's changes to the insets knowledge base, keyed by the composable's fully
     * qualified name, from `rules.compose.windowInsets.known`. A set replaces the built-in entry;
     * an empty set, written `none`, says the composable handles nothing.
     */
    val knownInsetsHandlers: Map<String, InsetsSet>,
    val compositionLocals: KotrailCompositionLocals,
)

/** What the knowledge base says about one library composable: the locals it reads, and those it provides to each lambda parameter. */
data class KotrailCompositionLocalKnowledge(
    val reads: Set<String>,
    val provides: Map<String, Set<String>>,
)

/** Tunables for the composition-locals rule. From `rules.compose.compositionLocals`. */
data class KotrailCompositionLocals(
    /** Locals the platform provides at every root (fully qualified property names); reads of these are never reported. */
    val platform: List<String>,
    /** Locals that must be provided even though their default does not throw. */
    val required: List<String>,
    /** Functions whose composable lambda argument is a root of composition: `setContent`, `Window`, ... */
    val roots: List<String>,
    /** Library composables described under `known`, keyed by fully qualified name. */
    val known: Map<String, KotrailCompositionLocalKnowledge>,
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

/** Tunables for the narrow-model-parameters rule. From `rules.narrowModelParameters`. */
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

/** Tunables for the prefer-function-references rule. From `rules.preferFunctionReferences`. */
data class KotrailPreferFunctionReferences(
    /** Only lambdas that can become one of these reference forms are reported. */
    val forms: Set<ReferenceForm>,
)

/** Tunables for the comment-length rule. From `rules.commentLength`; `0` means unlimited. */
data class KotrailCommentSettings(
    /** Maximum lines for a block comment or a run of consecutive `//` lines. */
    val maxLines: Int,
    /** Maximum lines for a KDoc comment. */
    val maxKDocLines: Int,
)

/** Tunables for the no-FQN-references rule. From `rules.noFqnReferences`. */
data class KotrailNoFqnReferences(
    /** Package prefixes whose members may be referenced fully qualified. */
    val allow: List<String>,
)

/** One entry of the forbidden-call rule: calls matching [predicate] are reported under [name]. */
data class KotrailForbiddenCallEntry(
    val name: String,
    val predicate: CallPredicate,
)

/** Tunables for the forbidden-call rule. From `rules.forbiddenCall`. */
data class KotrailForbiddenCall(
    /**
     * The entries, from the `calls` map of named call predicates and from the plain `functions`
     * list, whose fully qualified names become `fqn(...)` entries named after themselves. Empty
     * means the rule has nothing to report.
     */
    val entries: List<KotrailForbiddenCallEntry>,
)

/** Tunables for the must-be-serializable rule. From `rules.mustBeSerializable`. */
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
 * Tunables for the visibility-policy rule. From `rules.visibilityPolicy`. Each holds a
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
 * [annotation]. [name] is what the policy was configured under (`rules.requiredAnnotation.policies`)
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

/** Tunables for the no-data-class-in-public-API rule. From `rules.noDataClassInPublicApi`. */
data class KotrailNoDataClassInPublicApi(
    val scope: PublicApiScope,
)

/** Tunables for the function-length rule. From `rules.functionLength`; `0` means unlimited. */
data class KotrailFunctionLength(
    /** Most lines of code a function body may have. */
    val maxLines: Int,
    /** Most lines of code a `@Composable` function body may have; UI trees run longer than logic. */
    val maxComposableLines: Int,
)

/** Tunables for the named-arguments rule. From `rules.namedArgumentsForRepeatedTypes`. */
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

/** Tunables for the test rules. From `test.annotations` and `rules.test.naming`. */
data class KotrailTest(
    /**
     * Fully qualified annotations that mark a function as a test. Replacing the list is how a
     * project teaches Kotrail about its own framework; an empty list stops the test rules from
     * recognizing anything.
     */
    val annotations: List<String>,
    val namingStyle: TestNamingStyle,
    /** Functions (fully qualified) that wait real time; reported anywhere in a test. */
    val sleepFunctions: List<String>,
    /** Functions (fully qualified) whose lambda runs on virtual time, where `delay` is free. */
    val virtualTimeFunctions: List<String>,
    /** A backticked test name must have at least this many words; 1 accepts any name. */
    val minNameWords: Int,
)

/**
 * Rule settings resolved once per compilation. Sources, in increasing precedence: built-in
 * defaults, the YAML files passed through the `configFile` option (in the order they are
 * passed, deep-merged, a later file's entries winning key by key and `~` taking a key away),
 * and individual plugin options, which are dotted paths into the same tree.
 *
 * The shape of the tree is [ConfigSchema]'s: `rules.<rule>` is a scalar shorthand (`off`,
 * `on`, `error`, `warning`) or a mapping of `enabled`, `severity`, `note`, `exclude`, and the
 * rule's own settings.
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
    /** The `rules.requiredAnnotation.policies` entries, by name. */
    val requiredAnnotations: List<KotrailRequiredAnnotation>,
) {
    fun isEnabled(rule: KotrailRule): Boolean = switches[rule] ?: rule.defaultEnabled

    fun severity(rule: KotrailRule): Severity = severities[rule] ?: rule.defaultSeverity

    /**
     * The project's own text for this rule, appended to the built-in message and already prefixed
     * with a space, or empty. A rule's own `note` wins over the top-level `note`.
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
        val DEFAULT_SIDE_EFFECT_TYPES: List<String> = listOf("kotlinx.coroutines.Job", "kotlinx.coroutines.Deferred")
        val DEFAULT_HARDCODED_STRING_PARAMETERS: List<String> = listOf("text", "label", "title", "placeholder", "contentDescription", "message")
        val DEFAULT_TEST_SLEEP_FUNCTIONS: List<String> = listOf("java.lang.Thread.sleep", "android.os.SystemClock.sleep", "java.util.concurrent.TimeUnit.sleep")
        val DEFAULT_TEST_VIRTUAL_TIME_FUNCTIONS: List<String> = listOf("kotlinx.coroutines.test.runTest")
        val DEFAULT_SERIALIZATION_REQUIRED_FOR: List<String> = listOf("androidx.compose.runtime.saveable.rememberSerializable")
        const val DEFAULT_MAX_UNUSED_MODEL_PROPERTIES = 3
        val DEFAULT_NARROW_MODEL_SCOPE = NarrowModelParametersScope.COMPOSABLES
        val DEFAULT_REFERENCE_FORMS: Set<ReferenceForm> = ReferenceForm.entries.toSet()
        const val DEFAULT_COMMENT_MAX_LINES = 5
        const val DEFAULT_KDOC_MAX_LINES = 0
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
        val DEFAULT_LOCALS_ROOTS: List<String> = listOf(
            "androidx.activity.compose.setContent",
            "androidx.compose.ui.window.Window",
            "androidx.compose.ui.window.application",
            "androidx.compose.ui.window.singleWindowApplication",
            "androidx.compose.ui.window.ComposeUIViewController",
            "androidx.compose.ui.window.ComposeViewport",
            "androidx.compose.ui.window.CanvasBasedWindow",
        )

        /** The value of an entry that says "nothing": no insets handled, no locals read or provided. */
        const val NONE = "none"

        /** What a user-chosen entry name may look like: letters, digits, dots, `_` and `-`, starting with a letter. */
        val ENTRY_NAME = Regex("[A-Za-z][A-Za-z0-9_.-]*")

        @OptIn(ExperimentalCompilerApi::class)
        fun from(configuration: CompilerConfiguration): KotrailConfig = try {
            val tree = loadTree(
                configuration.get(KotrailConfigurationKeys.CONFIG_FILE).orEmpty(),
                configuration.get(KotrailConfigurationKeys.OPTIONS).orEmpty(),
            )
            Reader(tree).read()
        } catch (e: ConfigException) {
            throw CliOptionProcessingException("Kotrail configuration: ${e.message}")
        }

        /**
         * The configuration tree: every file parsed and desugared, merged in order, then every
         * option applied as a patch on top. Unknown keys are rejected as each layer is read.
         */
        internal fun loadTree(files: List<String>, options: List<String>): ConfigNode.Mapping {
            var tree: ConfigNode = ConfigNode.Mapping(LinkedHashMap(), "<defaults>")
            for (path in files) {
                val file = File(path)
                if (!file.isFile) throw ConfigException("config file not found: $path")
                val parsed = KotrailYaml.parse(file.name, file.readText())
                validate(parsed)
                tree = ConfigTree.merge(tree, desugar(parsed)) ?: tree
            }
            for (option in options) {
                val patch = optionPatch(option)
                validate(patch)
                tree = ConfigTree.merge(tree, desugar(patch)) ?: tree
            }
            return tree as ConfigNode.Mapping
        }

        /**
         * A plugin option as a tree patch. The option name is a dotted path in which a rule key
         * keeps its own dots (`rules.compose.nesting.maxDepth`); the value is read the way the
         * file would read it: a list is comma separated, a named entry is `<name>=<value>`, and an
         * empty value is `~`.
         */
        internal fun optionPatch(option: String): ConfigNode.Mapping {
            val eq = option.indexOf('=')
            if (eq < 0) throw ConfigException("option '$option' is not key=value")
            val name = option.substring(0, eq).trim()
            val value = option.substring(eq + 1)
            val at = "option $name"
            val (segments, kind) = optionPath(name) ?: throw ConfigException("unknown option '$name'")
            val leaf: ConfigNode = when {
                value.isBlank() -> ConfigNode.Null(at)
                kind == ConfigSchema.Kind.LIST -> ConfigNode.Sequence(parseList(value).map { ConfigNode.Scalar(it, at) }, at)
                kind == ConfigSchema.Kind.ENTRIES -> {
                    val inner = value.indexOf('=')
                    if (inner < 0) throw ConfigException("option '$name' takes '<name>=<value>', got '$value'")
                    val entry = value.substring(0, inner).trim()
                    val entryValue = value.substring(inner + 1)
                    ConfigNode.Mapping(
                        linkedMapOf(entry to (if (entryValue.isBlank()) ConfigNode.Null(at) else ConfigNode.Scalar(entryValue.trim(), at))),
                        at,
                    )
                }
                else -> ConfigNode.Scalar(value.trim(), at)
            }
            return ConfigTree.pathTo(segments, leaf, at) as ConfigNode.Mapping
        }

        /** The path segments and kind of an option name, or `null` if no such option exists. */
        internal fun optionPath(name: String): Pair<List<String>, ConfigSchema.Kind>? {
            ConfigSchema.TOP_LEVEL.firstOrNull { it.name == name }?.let { return listOf(it.name) to it.kind }
            if (name.startsWith("test.")) {
                ConfigSchema.TEST.firstOrNull { "test." + it.name == name }?.let { return listOf("test", it.name) to it.kind }
                return null
            }
            if (!name.startsWith("rules.")) return null
            val rest = name.removePrefix("rules.")
            // A rule key may contain dots, so the longest rule key that is a prefix wins.
            val rule = KotrailRule.entries.filter { rest == it.key || rest.startsWith(it.key + ".") }.maxByOrNull { it.key.length }
                ?: return null
            if (rest == rule.key) return listOf("rules", rule.key) to ConfigSchema.Kind.STRING
            val setting = rest.removePrefix(rule.key + ".")
            val known = (ConfigSchema.RESERVED + ConfigSchema.settingsOf(rule)).firstOrNull { it.name == setting } ?: return null
            return listOf("rules", rule.key, setting) to known.kind
        }

        /** Every option name the command line accepts, with the kind of value it takes. */
        val OPTION_NAMES: List<Pair<String, ConfigSchema.Kind>> =
            ConfigSchema.TOP_LEVEL.map { it.name to it.kind } +
                ConfigSchema.TEST.map { "test." + it.name to it.kind } +
                KotrailRule.entries.flatMap { rule ->
                    listOf("rules." + rule.key to ConfigSchema.Kind.STRING) +
                        (ConfigSchema.RESERVED + ConfigSchema.settingsOf(rule)).map { "rules.${rule.key}.${it.name}" to it.kind }
                }

        /** `rules.<rule>: off` and friends become the mapping they stand for, so that layers merge key by key. */
        private fun desugar(tree: ConfigNode.Mapping): ConfigNode.Mapping {
            val rules = tree["rules"] as? ConfigNode.Mapping ?: return tree
            val rewritten = LinkedHashMap<String, ConfigNode>()
            for ((key, node) in rules.entries) {
                rewritten[key] = if (node is ConfigNode.Scalar) {
                    val rule = ConfigSchema.ruleByKey(key)
                    val patch = LinkedHashMap<String, ConfigNode>()
                    when (node.value) {
                        "off", "on" -> {
                            if (rule?.hasSwitch == false) throw ConfigException("${node.at}: '$key' has no switch; it is switched with the rule that owns it")
                            patch["enabled"] = ConfigNode.Scalar((node.value == "on").toString(), node.at)
                        }
                        "error", "warning" -> {
                            if (rule?.hasSwitch != false) patch["enabled"] = ConfigNode.Scalar("true", node.at)
                            patch["severity"] = ConfigNode.Scalar(node.value, node.at)
                        }
                        else -> throw ConfigException("${node.at}: '$key' must be off, on, error, warning, or a mapping; got '${node.value}'")
                    }
                    ConfigNode.Mapping(patch, node.at)
                } else {
                    node
                }
            }
            val top = LinkedHashMap(tree.entries)
            top["rules"] = ConfigNode.Mapping(rewritten, rules.at)
            return ConfigNode.Mapping(top, tree.at)
        }

        /** Rejects unknown keys and wrong shapes with the offending position, before anything is merged. */
        private fun validate(tree: ConfigNode.Mapping) {
            val topNames = ConfigSchema.TOP_LEVEL.map { it.name } + listOf("test", "rules")
            for (key in tree.entries.keys) {
                if (key !in topNames) throw ConfigException("${tree.keyAt(key)}: unknown key '$key'; expected one of ${topNames.joinToString()}")
            }
            (tree["test"] as? ConfigNode.Mapping)?.let { test ->
                for (key in test.entries.keys) {
                    if (ConfigSchema.TEST.none { it.name == key }) throw ConfigException("${test.keyAt(key)}: unknown key '$key' under test; expected ${ConfigSchema.TEST.joinToString { it.name }}")
                }
            }
            val rules = tree["rules"] ?: return
            if (rules !is ConfigNode.Mapping) {
                if (rules is ConfigNode.Null) return
                throw ConfigException("${rules.at}: rules must be a mapping of rule keys")
            }
            for ((key, node) in rules.entries) {
                val rule = ConfigSchema.ruleByKey(key)
                    ?: throw ConfigException("${rules.keyAt(key)}: unknown rule '$key'; rules are named by their full key, for example compose.nesting")
                if (node !is ConfigNode.Mapping) continue
                val allowed = ConfigSchema.RESERVED.filter { rule.hasSwitch || it.name != "enabled" } + ConfigSchema.settingsOf(rule)
                for (setting in node.entries.keys) {
                    if (allowed.none { it.name == setting }) {
                        throw ConfigException("${node.keyAt(setting)}: unknown key '$setting' under rules.$key; expected one of ${allowed.joinToString { it.name }}")
                    }
                }
            }
        }

        /** Comma-separated values, trimmed, empties dropped. */
        fun parseList(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Reads the merged tree into settings, converting and checking every value where it is used. */
    private class Reader(private val tree: ConfigNode.Mapping) {
        private val rules = tree["rules"] as? ConfigNode.Mapping

        fun read(): KotrailConfig {
            val switches = KotrailRule.switchable.associateWith { rule -> boolean(ruleNode(rule), "enabled") ?: rule.defaultEnabled }
            val severities = KotrailRule.entries.associateWith { rule -> enumValue(rule, "severity", listOf("error", "warning"))?.let { parseSeverity(it) } ?: rule.defaultSeverity }
            val projectNote = string(tree, "note")
            val notes = KotrailRule.entries.associateWith { rule ->
                val text = string(ruleNode(rule), "note") ?: projectNote
                if (text.isNullOrBlank()) "" else " " + text.trim()
            }
            val excludes = KotrailExcludes(
                everywhere = predicate(tree, "exclude"),
                perRule = KotrailRule.entries.mapNotNull { rule -> predicate(ruleNode(rule), "exclude")?.let { rule to it } }.toMap(),
            )
            val test = tree["test"] as? ConfigNode.Mapping
            return KotrailConfig(
                enabled = boolean(tree, "enabled") ?: true,
                switches = switches,
                severities = severities,
                notes = notes,
                excludes = excludes,
                compose = KotrailComposeSettings(
                    maxNesting = int(KotrailRule.COMPOSE_NESTING, "maxDepth") ?: DEFAULT_COMPOSE_MAX_NESTING,
                    trailingLambdaAllowedPackages = list(KotrailRule.COMPOSE_NO_TRAILING_CALLBACK, "allowedPackages") ?: DEFAULT_TRAILING_LAMBDA_ALLOWED_PACKAGES,
                    previewRequireFor = enumValue(KotrailRule.COMPOSE_PREVIEW_REQUIRED, "scope", PreviewScope.entries.map { it.key })?.let { PreviewScope.fromKey(it)!! } ?: DEFAULT_PREVIEW_REQUIRE_FOR,
                    maxComposablesPerFile = int(KotrailRule.COMPOSE_COMPOSABLES_PER_FILE, "max") ?: DEFAULT_MAX_COMPOSABLES_PER_FILE,
                    countOverloadsSeparately = boolean(ruleNode(KotrailRule.COMPOSE_COMPOSABLES_PER_FILE), "countOverloadsSeparately") ?: false,
                    sideEffectTypes = list(KotrailRule.COMPOSE_NO_SIDE_EFFECT_IN_COMPOSITION, "types") ?: DEFAULT_SIDE_EFFECT_TYPES,
                    sideEffectFunctions = list(KotrailRule.COMPOSE_NO_SIDE_EFFECT_IN_COMPOSITION, "functions").orEmpty(),
                    hardcodedStringParameters = list(KotrailRule.COMPOSE_NO_HARDCODED_STRING, "parameters") ?: DEFAULT_HARDCODED_STRING_PARAMETERS,
                    knownInsetsHandlers = entries(KotrailRule.COMPOSE_WINDOW_INSETS, "known") { node -> insetsSpec(node) },
                    compositionLocals = KotrailCompositionLocals(
                        platform = list(KotrailRule.COMPOSE_COMPOSITION_LOCALS, "platform").orEmpty(),
                        required = list(KotrailRule.COMPOSE_COMPOSITION_LOCALS, "required").orEmpty(),
                        roots = list(KotrailRule.COMPOSE_COMPOSITION_LOCALS, "roots") ?: DEFAULT_LOCALS_ROOTS,
                        known = entries(KotrailRule.COMPOSE_COMPOSITION_LOCALS, "known") { node -> localsSpec(node) },
                    ),
                ),
                narrowModelParameters = KotrailNarrowModelParameters(
                    maxUnusedProperties = int(KotrailRule.NARROW_MODEL_PARAMETERS, "maxUnusedProperties") ?: DEFAULT_MAX_UNUSED_MODEL_PROPERTIES,
                    scope = enumValue(KotrailRule.NARROW_MODEL_PARAMETERS, "scope", NarrowModelParametersScope.entries.map { it.key })?.let { NarrowModelParametersScope.fromKey(it)!! } ?: DEFAULT_NARROW_MODEL_SCOPE,
                ),
                preferFunctionReferences = KotrailPreferFunctionReferences(
                    forms = list(KotrailRule.PREFER_FUNCTION_REFERENCES, "forms")?.let { forms ->
                        val parsed = forms.map { ReferenceForm.fromKey(it) ?: fail(KotrailRule.PREFER_FUNCTION_REFERENCES, "forms", "accepts a subset of ${ReferenceForm.entries.joinToString { f -> f.key }}, got '$it'") }.toSet()
                        if (parsed.isEmpty()) fail(KotrailRule.PREFER_FUNCTION_REFERENCES, "forms", "must list at least one form")
                        parsed
                    } ?: DEFAULT_REFERENCE_FORMS,
                ),
                comments = KotrailCommentSettings(
                    maxLines = int(KotrailRule.COMMENT_LENGTH, "maxLines") ?: DEFAULT_COMMENT_MAX_LINES,
                    maxKDocLines = int(KotrailRule.COMMENT_LENGTH, "maxKDocLines") ?: DEFAULT_KDOC_MAX_LINES,
                ),
                noFqnReferences = KotrailNoFqnReferences(allow = list(KotrailRule.NO_FQN_REFERENCES, "allow").orEmpty()),
                forbiddenCall = KotrailForbiddenCall(
                    entries = list(KotrailRule.FORBIDDEN_CALL, "functions").orEmpty().map { KotrailForbiddenCallEntry(it, plainForbiddenName(it)) } +
                        entries(KotrailRule.FORBIDDEN_CALL, "calls") { node -> callPredicate(node) }.map { (name, predicate) -> KotrailForbiddenCallEntry(name, predicate) },
                ),
                namedArguments = KotrailNamedArguments(minSameTypeArguments = int(KotrailRule.NAMED_ARGUMENTS_FOR_REPEATED_TYPES, "minArguments") ?: DEFAULT_MIN_SAME_TYPE_ARGUMENTS),
                serialization = KotrailSerialization(requiredFor = list(KotrailRule.MUST_BE_SERIALIZABLE, "requiredFor") ?: DEFAULT_SERIALIZATION_REQUIRED_FOR),
                test = KotrailTest(
                    annotations = test?.get("annotations")?.let { list(it, "test.annotations") } ?: DEFAULT_TEST_ANNOTATIONS,
                    namingStyle = enumValue(KotrailRule.TEST_NAMING, "style", TestNamingStyle.entries.map { it.key })?.let { TestNamingStyle.fromKey(it)!! } ?: DEFAULT_TEST_NAMING_STYLE,
                    minNameWords = int(KotrailRule.TEST_NAMING, "minWords") ?: DEFAULT_TEST_MIN_NAME_WORDS,
                    sleepFunctions = list(KotrailRule.TEST_NO_SLEEP, "functions") ?: DEFAULT_TEST_SLEEP_FUNCTIONS,
                    virtualTimeFunctions = list(KotrailRule.TEST_NO_SLEEP, "virtualTime") ?: DEFAULT_TEST_VIRTUAL_TIME_FUNCTIONS,
                ),
                functionLength = KotrailFunctionLength(
                    maxLines = int(KotrailRule.FUNCTION_LENGTH, "maxLines") ?: DEFAULT_FUNCTION_MAX_LINES,
                    maxComposableLines = int(KotrailRule.FUNCTION_LENGTH, "maxComposableLines") ?: DEFAULT_COMPOSABLE_MAX_LINES,
                ),
                noDataClassInPublicApi = KotrailNoDataClassInPublicApi(
                    scope = enumValue(KotrailRule.NO_DATA_CLASS_IN_PUBLIC_API, "scope", PublicApiScope.entries.map { it.key })?.let { PublicApiScope.fromKey(it)!! } ?: DEFAULT_NO_DATA_CLASS_SCOPE,
                ),
                visibilityPolicy = KotrailVisibilityPolicy(
                    private = predicate(ruleNode(KotrailRule.VISIBILITY_POLICY), "private"),
                    internal = predicate(ruleNode(KotrailRule.VISIBILITY_POLICY), "internal"),
                ),
                requiredAnnotations = entries(KotrailRule.REQUIRED_ANNOTATION, "policies") { node -> node }
                    .map { (name, node) -> requiredAnnotation(name, node) },
            )
        }

        private fun ruleNode(rule: KotrailRule): ConfigNode.Mapping? = rules?.get(rule.key) as? ConfigNode.Mapping

        private fun fail(node: ConfigNode, message: String): Nothing = throw ConfigException("${node.at}: $message")

        private fun fail(rule: KotrailRule, setting: String, message: String): Nothing {
            val node = ruleNode(rule)?.get(setting)
            throw ConfigException("${node?.at ?: "rules.${rule.key}.$setting"}: rules.${rule.key}.$setting $message")
        }

        private fun scalar(mapping: ConfigNode.Mapping?, key: String): ConfigNode.Scalar? = when (val node = mapping?.get(key)) {
            null, is ConfigNode.Null -> null
            is ConfigNode.Scalar -> node
            else -> fail(node, "'$key' must be a single value")
        }

        private fun string(mapping: ConfigNode.Mapping?, key: String): String? = scalar(mapping, key)?.value

        private fun boolean(mapping: ConfigNode.Mapping?, key: String): Boolean? = scalar(mapping, key)?.let {
            when (it.value.lowercase()) {
                "true" -> true
                "false" -> false
                else -> fail(it, "'$key' must be true or false, got '${it.value}'")
            }
        }

        private fun int(rule: KotrailRule, key: String): Int? = scalar(ruleNode(rule), key)?.let {
            it.value.toIntOrNull() ?: fail(it, "'$key' must be an integer, got '${it.value}'")
        }

        private fun enumValue(rule: KotrailRule, key: String, values: List<String>): String? = scalar(ruleNode(rule), key)?.let {
            values.firstOrNull { v -> v.equals(it.value.trim(), ignoreCase = true) }
                ?: fail(it, "'$key' must be one of ${values.joinToString()}, got '${it.value}'")
        }

        /** A list setting: a sequence in the file, or a comma-separated scalar, for the same value either way. */
        private fun list(node: ConfigNode, what: String): List<String> = when (node) {
            is ConfigNode.Sequence -> node.items.map { item ->
                (item as? ConfigNode.Scalar)?.value ?: fail(item, "'$what' must list single values")
            }
            is ConfigNode.Scalar -> parseList(node.value)
            is ConfigNode.Null -> emptyList()
            is ConfigNode.Mapping -> fail(node, "'$what' must be a list")
        }

        private fun list(rule: KotrailRule, key: String): List<String>? = ruleNode(rule)?.get(key)?.let { list(it, key) }

        private fun predicate(mapping: ConfigNode.Mapping?, key: String): ExcludePredicate? = scalar(mapping, key)?.let {
            if (it.value.isBlank()) return null
            try {
                ExcludeParser.parse(it.value)
            } catch (e: ExcludeParser.ExcludeSyntaxException) {
                fail(it, e.message.orEmpty())
            }
        }

        /** The named entries of a map setting, sorted by name, each converted by [convert]. */
        private fun <T> entries(rule: KotrailRule, key: String, convert: (ConfigNode) -> T): Map<String, T> {
            val node = ruleNode(rule)?.get(key) ?: return emptyMap()
            if (node is ConfigNode.Null) return emptyMap()
            val mapping = node as? ConfigNode.Mapping ?: fail(node, "'$key' must be a mapping of names to values")
            return mapping.entries.entries
                .filter { it.value !is ConfigNode.Null }
                .sortedBy { it.key }
                .associate { (name, value) ->
                    if (!ENTRY_NAME.matches(name)) fail(value, "'$name' is not a valid entry name: a letter, then letters, digits, '.', '_' or '-'")
                    name to convert(value)
                }
        }

        private fun callPredicate(node: ConfigNode): CallPredicate {
            val scalar = node as? ConfigNode.Scalar ?: fail(node, "a forbidden call is a call predicate, for example fqn(kotlin.io.println)")
            return try {
                CallPredicateParser.parse(scalar.value)
            } catch (e: ExcludeParser.ExcludeSyntaxException) {
                fail(node, e.message.orEmpty())
            }
        }

        /** A policy: `{where: <predicate>, annotation: <fqn>}`, or the one-line `<predicate> -> <fqn>`. */
        private fun requiredAnnotation(name: String, node: ConfigNode): KotrailRequiredAnnotation {
            val where: String
            val annotation: String
            when (node) {
                is ConfigNode.Mapping -> {
                    for ((key, value) in node.entries) if (key != "where" && key != "annotation") fail(value, "unknown key '$key' in a policy; expected where and annotation")
                    where = string(node, "where") ?: fail(node, "a policy needs 'where', a predicate over declarations")
                    annotation = string(node, "annotation") ?: fail(node, "a policy needs 'annotation', a fully qualified name")
                }
                is ConfigNode.Scalar -> {
                    val arrow = node.value.lastIndexOf("->")
                    if (arrow < 0) fail(node, "a policy is a mapping with where and annotation, or '<predicate> -> <annotation fqn>'")
                    where = node.value.substring(0, arrow)
                    annotation = node.value.substring(arrow + 2).trim()
                }
                else -> fail(node, "a policy is a mapping with where and annotation")
            }
            if (annotation.isEmpty() || annotation.any { it.isWhitespace() }) fail(node, "'annotation' must be one fully qualified name, got '$annotation'")
            val predicate = try {
                ExcludeParser.parse(where)
            } catch (e: ExcludeParser.ExcludeSyntaxException) {
                fail(node, e.message.orEmpty())
            }
            return KotrailRequiredAnnotation(name, predicate, annotation.removePrefix("@"))
        }

        /**
         * An insets entry: one `Type` or `Type:Side+Side` scalar, a sequence of them, a
         * comma-separated scalar, or `none` for a composable that handles nothing.
         */
        private fun insetsSpec(node: ConfigNode): InsetsSet {
            val specs = when (node) {
                is ConfigNode.Scalar -> if (node.value.trim().equals(NONE, ignoreCase = true)) return InsetsSet.EMPTY else parseList(node.value)
                is ConfigNode.Sequence -> node.items.map { (it as? ConfigNode.Scalar)?.value ?: fail(it, "an insets entry lists Type or Type:Side+Side values") }
                else -> fail(node, "an insets entry is a value, a list, or none")
            }
            var result = InsetsSet.EMPTY
            for (entry in specs) {
                val parts = entry.split(':', limit = 2).map { it.trim() }
                if (parts[0].firstOrNull()?.isUpperCase() != true) fail(node, "'${parts[0]}' is not a WindowInsetsType entry; write it as SystemBars, Ime, ...")
                val sides = if (parts.size == 2) {
                    parts[1].split('+').map { it.trim() }.fold(Sides.NONE) { acc, side ->
                        acc or (Sides.fromName(side) ?: fail(node, "unknown side '$side' in '$entry'; use Top, Bottom, Left, Right, Start, End, Horizontal, Vertical, or All"))
                    }
                } else {
                    Sides.ALL
                }
                val insets = InsetsSet.fromTypeName(parts[0], sides) ?: fail(node, "unknown insets type '${parts[0]}' in '$entry'; use a WindowInsetsType name such as SystemBars")
                result = result.union(insets)
            }
            return result
        }

        /**
         * A composition-locals entry: `{reads: [...], provides: {param: [...]}}`, `none`, or the
         * one-line `local, param:local, ...` form.
         */
        private fun localsSpec(node: ConfigNode): KotrailCompositionLocalKnowledge {
            when (node) {
                is ConfigNode.Mapping -> {
                    for ((key, value) in node.entries) if (key != "reads" && key != "provides") fail(value, "unknown key '$key' in a locals entry; expected reads and provides")
                    val reads = node["reads"]?.let { list(it, "reads") }.orEmpty().toSet()
                    val provides = when (val p = node["provides"]) {
                        null, is ConfigNode.Null -> emptyMap()
                        is ConfigNode.Mapping -> p.entries.mapValues { (param, locals) -> list(locals, param).toSet() }
                        else -> fail(p, "'provides' maps a lambda parameter name to the locals provided to it")
                    }
                    return KotrailCompositionLocalKnowledge(reads, provides)
                }
                is ConfigNode.Scalar -> {
                    if (node.value.trim().equals(NONE, ignoreCase = true)) return KotrailCompositionLocalKnowledge(emptySet(), emptyMap())
                    val reads = LinkedHashSet<String>()
                    val provides = LinkedHashMap<String, MutableSet<String>>()
                    for (entry in parseList(node.value)) {
                        val parts = entry.split(':', limit = 2).map { it.trim() }
                        if (parts.any { it.isEmpty() || it.any(Char::isWhitespace) }) fail(node, "'$entry' is not a fully qualified local or '<parameter>:<local>'")
                        if (parts.size == 2) provides.getOrPut(parts[0]) { LinkedHashSet() } += parts[1] else reads += parts[0]
                    }
                    return KotrailCompositionLocalKnowledge(reads, provides)
                }
                else -> fail(node, "a locals entry is a mapping with reads and provides, or none")
            }
        }

        private fun parseSeverity(value: String): Severity = if (value.equals("warning", ignoreCase = true)) Severity.WARNING else Severity.ERROR

        /**
         * What a plain `functions` name stands for: the function of that name, the constructors
         * of a class of that name, and, for `Type.name`, an extension `name` called on a `Type`
         * receiver, since that is how a reader sees `GlobalScope.launch { }`.
         */
        private fun plainForbiddenName(fqn: String): CallPredicate {
            var predicate: CallPredicate = CallPredicate.Or(CallPredicate.FqnIs(Glob(fqn)), CallPredicate.Constructs(Glob(fqn)))
            val dot = fqn.lastIndexOf('.')
            if (dot > 0) {
                predicate = CallPredicate.Or(
                    predicate,
                    CallPredicate.And(
                        // The extension may be top level in any package, the default one included.
                        CallPredicate.Or(CallPredicate.FqnIs(Glob(fqn.substring(dot + 1))), CallPredicate.FqnIs(Glob("*." + fqn.substring(dot + 1)))),
                        CallPredicate.ReceiverIs(fqn.substring(0, dot)),
                    ),
                )
            }
            return predicate
        }
    }
}

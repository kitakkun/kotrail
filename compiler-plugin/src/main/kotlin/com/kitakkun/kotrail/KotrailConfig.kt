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
import com.kitakkun.kotrail.fir.compose.stability.StableTypeMatcher
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
    /** Packages whose composables emit nothing themselves (effects, providers); calls to them do not make a composable a UI one. */
    val nonUiPackages: List<String>,
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
    /** Patterns of types the project declares stable, in the grammar of the Compose stability configuration file. */
    val stableTypes: List<String>,
    /** Whether a `@Composable` function-typed property of a model handed to a UI composable is allowed. */
    val allowComposableSlots: Boolean,
    /** The complexity rule's limit and hotspot threshold. */
    val complexity: KotrailComplexity,
    /** Whether an assignment to a global `var` inside a composable's event handler is reported, besides those made during composition. */
    val globalStateHandlerWrites: Boolean,
    /** How many previews of one file must build the same model inline before they are reported. */
    val previewParameterMinPreviews: Int,
    /** Fully qualified functions whose trailing lambda is keyed by their other arguments: remember, LaunchedEffect and the like. */
    val rememberKeysFunctions: List<String>,
    val previewCoverage: KotrailPreviewCoverage,
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

/** Tunables for the preview-coverage rule. From `rules.compose.previewCoverage`. */
data class KotrailPreviewCoverage(
    /** Exact package names whose top-level UI composables must be called by a preview in this compilation. */
    val packages: List<String>,
    /** Which of those composables count: public ones, or internal ones too. */
    val visibility: PreviewScope,
    /** Globs over fully qualified names of composables left out. */
    val excludeNames: List<String>,
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

/** Tunables for the null-chain-length rule. From `rules.nullChainLength`; `0` switches a count off. */
data class KotrailNullChain(
    /** Maximum `?:` fallbacks in one expression, a trailing `?: return` / `?: throw` not counted. */
    val maxElvis: Int,
    /** Maximum `?.` along one receiver chain. */
    val maxSafeCalls: Int,
)

/** How an object case of a sealed type is written in a `when` branch. */
enum class WhenBranchStyle(val key: String) {
    /** `is Cancel ->`, the same shape as the class cases. */
    IS("is"),

    /** `Cancel ->`, the shortest form. */
    OBJECT("object");

    companion object {
        fun fromKey(key: String): WhenBranchStyle? = entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
    }
}

/** A project's own chain idiom: `inner(arg).outer()` is written as the [replacement] function (fully qualified) with `arg`. */
data class ChainIdiom(val inner: String, val outer: String, val replacement: String)

/** A project's own call idiom: `fqn(literal)` is written as the [replacement] function (fully qualified) with no argument. */
data class CallIdiom(val fqn: String, val literal: String, val replacement: String)

/** Tunables for the narrow-local-scope rule. From `rules.narrowLocalScope`. */
/** Tunables for the unloadable-code rule. From `rules.unloadableCode`. */
data class KotrailUnloadableCode(
    /** Globs over fully qualified functions that register something with the platform for the rest of its life. */
    val registrations: List<String>,
    /** Fully qualified types an argument of which scopes a registration to a lifetime. */
    val disposableTypes: List<String>,
)

data class KotrailNarrowLocalScope(
    /** A local whose first use is more than this many lines below its declaration is reported; `0` switches the distance check off. */
    val maxDistance: Int,
)

/** Tunables for the live-variable-budget rule. From `rules.liveVariableBudget`. */
data class KotrailLiveVariables(
    /** Variables (locals and parameters) that may be live at one statement; `0` disables the rule. */
    val max: Int,
)

/** Tunables for the prefer-idiom rule. From `rules.preferIdiom`. */
data class KotrailPreferIdiom(
    /** Idiom keys (`emptiness`, `negation`, `nullOrEmpty`, `chain`, `elvis`) the project does not want asked for. */
    val disabled: List<String>,
    /** From `chains`, each written `<inner fqn> then <outer fqn> -> <name>`. */
    val chains: List<ChainIdiom>,
    /** From `calls`, each written `<fqn>(<literal>) -> <name>`. */
    val calls: List<CallIdiom>,
)

/** Tunables for the sealed-when-branch-style rule. From `rules.sealedWhenBranchStyle`. */
data class KotrailSealedWhen(
    val style: WhenBranchStyle,
)

/** Tunables for the implicit-receivers rule. From `rules.implicitReceivers`. */
data class KotrailImplicitReceivers(
    /** Whether a bare name that two implicit receivers in scope could supply is reported. */
    val qualifyAmbiguous: Boolean,
    /** Maximum implicit receivers in scope at once; `0` switches the count off. */
    val maxDepth: Int,
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

/** Tunables for the file-length rule. From `rules.fileLength`. */
data class KotrailFileLength(
    /** Most lines of code a file may have (blank, brace-only, comment, package and import lines excluded); 0 for unlimited. */
    val maxLines: Int,
    /** Most distinct top-level names a file may declare (private properties, previews and `actual` declarations aside); 0 for unlimited. */
    val maxTopLevelDeclarations: Int,
)

/** Tunables for the no-literal-loop rule. From `rules.noLiteralLoop`. */
data class KotrailLiteralLoop(
    /** Most elements a literal collection may have and still count as folded cases rather than a table; booleans count at any size. */
    val maxElements: Int,
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
/** Tunables for the Objective-C throws rule. From `rules.native.objcThrows`. */
data class KotrailObjCThrows(
    /** Package globs of the API a framework exports to Swift; empty means every public function of an Apple compilation. */
    val packages: List<Glob>,
)

/** Tunables for the composable complexity rule. From `rules.compose.complexity`. */
data class KotrailComplexity(
    /** Most points a composable may score; 0 switches the limit off (records are still written). */
    val maxScore: Int,
    /** Percent of a composable's points a block must carry to be named as the place to extract. */
    val hotspotShare: Int,
    /** Fully qualified functions that produce a source of state besides the calls whose result is a `State`: a project's remember wrapper. */
    val stateFactories: List<String>,
)

/** Tunables for the must-close rule. From `rules.mustClose`. */
data class KotrailMustClose(
    /** Fully qualified factory functions whose result is a resource the caller owns, besides constructors of `AutoCloseable` classes. */
    val factories: List<String>,
    /** `AutoCloseable` types (subtypes included) that hold nothing worth closing: in-memory buffers, a project's registration handles. */
    val ignoredTypes: List<String>,
)

/** Tunables for the native-allocation-in-loop rule. From `rules.nativeAllocationInLoop`. */
data class KotrailNativeAllocation(
    /** Fully qualified types (subtypes included) whose instances hold native memory freed only by a cleaner. */
    val types: List<String>,
    /** Fully qualified factory functions that return such an instance. */
    val factories: List<String>,
    /** Fully qualified functions whose lambda runs once per item or frame, like a loop body. */
    val callbacks: List<String>,
)

/** Tunables for the weak-only-reference rule. From `rules.weakOnlyReference`. */
data class KotrailWeakOnlyReference(
    /** Fully qualified weak (or soft) reference types (subtypes included). */
    val types: List<String>,
)

/** Tunables for the catch-too-broad rule. From `rules.catchTooBroad`. */
data class KotrailCatchTooBroad(
    /** Fully qualified exception types a catch clause must not name. */
    val types: List<String>,
    /** `swallowed`: only clauses that let the failure go no further than a log line; `all`: every broad clause. */
    val report: String,
    /** Globs over fully qualified functions that only log; a failure handed to them alone counts as swallowed. */
    val loggers: List<String>,
)

/** Tunables for the unretained rule. From `rules.unretained`. */
data class KotrailUnretained(
    /** Fully qualified annotations that mark a parameter as not to be retained. */
    val annotations: List<String>,
    /** Fully qualified weak reference types (subtypes included), through which a parameter may be kept. */
    val weakTypes: List<String>,
)

/** Tunables of the delay-for-completion rule. From `rules.delayForCompletion`. */
data class KotrailAsyncWork(
    /** Fully qualified functions that wait a fixed time: `delay`, `Thread.sleep`. */
    val delays: List<String>,
    /** Fully qualified functions that start work that outlives the call: `launch`, `async`, `Thread.start`, a posted runnable. */
    val starters: List<String>,
)

/** One `requiredSupertype` policy: declarations matching [predicate] must extend or implement [supertype]. */
data class KotrailSupertypePolicy(
    val name: String,
    val predicate: ExcludePredicate,
    /** Fully qualified class or interface name. */
    val supertype: String,
)

/** One `dependencyRules` policy: code in packages matching [from] must not refer to packages matching [deny], unless they match [allow]. */
data class KotrailDependencyPolicy(
    val name: String,
    val from: Glob,
    val deny: List<Glob>,
    val allow: List<Glob>,
)

/** What counts as generated code, which every rule skips. From the top-level `generated` mapping. */
data class KotrailGenerated(
    /** Globs over source file paths, `/` separated. */
    val paths: List<Glob>,
    /** Fully qualified annotations on a file or a declaration. */
    val annotations: List<String>,
) {
    /** Whether a file at [path] (any separators) is generated by its path. */
    fun matchesPath(path: String): Boolean = paths.any { it.matches(path.replace('\\', '/')) }

    /** Whether a set of annotation names on a file or a declaration chain marks it generated. */
    fun matchesAnnotations(names: Set<String>): Boolean = annotations.any { it in names }
}

data class KotrailTest(
    /**
     * Fully qualified annotations that mark a function as a test. Replacing the list is how a
     * project teaches Kotrail about its own framework; an empty list stops the test rules from
     * recognizing anything.
     */
    val annotations: List<String>,
    val namingStyle: TestNamingStyle,
    /** Globs over fully qualified functions that assert or verify; a test that calls none is reported. */
    val assertions: List<String>,
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
    /** Directory of the per-file fix records, or `null` to record none (the `fixesDir` plugin option). */
    val fixesDir: String?,
    /** Directory of the per-file composable records, or `null` to record none (the `composablesDir` plugin option). */
    val composablesDir: String?,
    /** Composable records of associated compilations, for preview coverage (the `associatedComposablesDir` plugin option). */
    val associatedComposablesDirs: List<String>,
    /** Directory of the per-file unloadable-code records, or `null` to record none (the `unloadableDir` plugin option). */
    val unloadableDir: String?,
    /** Directory of the per-file composable complexity records, or `null` to record none (the `complexityDir` plugin option). */
    val complexityDir: String?,
    /** Record roots of the modules on the runtime class path, for the unloadable-code rule (the `bundledUnloadableDir` plugin option). */
    val bundledUnloadableDirs: List<String>,
    /** The build's root directory, for paths in messages that name a file of another module (the `rootDir` plugin option). */
    val rootDir: String?,
    private val switches: Map<KotrailRule, Boolean>,
    private val severities: Map<KotrailRule, Severity>,
    private val notes: Map<KotrailRule, String>,
    private val fixes: Map<KotrailRule, Boolean>,
    val excludes: KotrailExcludes,
    val compose: KotrailComposeSettings,
    val narrowModelParameters: KotrailNarrowModelParameters,
    val preferFunctionReferences: KotrailPreferFunctionReferences,
    val comments: KotrailCommentSettings,
    val nullChain: KotrailNullChain,
    val implicitReceivers: KotrailImplicitReceivers,
    val sealedWhen: KotrailSealedWhen,
    val preferIdiom: KotrailPreferIdiom,
    val narrowLocalScope: KotrailNarrowLocalScope,
    val unloadableCode: KotrailUnloadableCode,
    val liveVariables: KotrailLiveVariables,
    val noFqnReferences: KotrailNoFqnReferences,
    val forbiddenCall: KotrailForbiddenCall,
    val namedArguments: KotrailNamedArguments,
    val serialization: KotrailSerialization,
    val test: KotrailTest,
    val generated: KotrailGenerated,
    val nativeAllocation: KotrailNativeAllocation,
    val mustClose: KotrailMustClose,
    val objcThrows: KotrailObjCThrows,
    val weakOnlyReference: KotrailWeakOnlyReference,
    val catchTooBroad: KotrailCatchTooBroad,
    val unretained: KotrailUnretained,
    val requiredSupertypes: List<KotrailSupertypePolicy>,
    val asyncWork: KotrailAsyncWork,
    val dependencyPolicies: List<KotrailDependencyPolicy>,
    val functionLength: KotrailFunctionLength,
    val fileLength: KotrailFileLength,
    val literalLoop: KotrailLiteralLoop,
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

    /** Whether the rule's fixes are recorded for `kotrailFix`; the diagnostic is reported either way. */
    fun fixEnabled(rule: KotrailRule): Boolean = fixes[rule] ?: true

    companion object {
        const val DEFAULT_COMPOSE_MAX_NESTING = 5
        val DEFAULT_TRAILING_LAMBDA_ALLOWED_PACKAGES: List<String> = listOf("androidx.compose.runtime")
        val DEFAULT_PREVIEW_REQUIRE_FOR = PreviewScope.INTERNAL
        val DEFAULT_DISPOSABLE_TYPES = listOf("com.intellij.openapi.Disposable")

        /** JVM and AWT hooks that live as long as the process, and IntelliJ registrations that take an optional parent disposable. */
        val DEFAULT_REGISTRATIONS = listOf(
            "java.lang.Runtime.addShutdownHook",
            "java.lang.Thread.setDefaultUncaughtExceptionHandler",
            "java.awt.Toolkit.addAWTEventListener",
            "java.awt.KeyboardFocusManager.addPropertyChangeListener",
            "java.awt.KeyboardFocusManager.addKeyEventDispatcher",
            "com.intellij.util.messages.MessageBus.connect",
            "com.intellij.openapi.application.Application.addApplicationListener",
            "com.intellij.openapi.extensions.ExtensionPointName.addExtensionPointListener",
            "com.intellij.openapi.extensions.ExtensionPointName.addChangeListener",
            "com.intellij.openapi.editor.EditorFactory.addEditorFactoryListener",
            "com.intellij.openapi.vfs.VirtualFileManager.addVirtualFileListener",
            "com.intellij.openapi.project.ProjectManager.addProjectManagerListener",
        )
        val DEFAULT_NON_UI_PACKAGES: List<String> = listOf("androidx.compose.runtime")
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
        const val DEFAULT_MAX_ELVIS = 2
        val IDIOM_KEYS: List<String> = listOf("emptiness", "negation", "nullOrEmpty", "chain", "elvis")
        const val DEFAULT_LOCAL_MAX_DISTANCE = 5
        const val DEFAULT_MAX_LIVE_VARIABLES = 7
        private val CHAIN_IDIOM = Regex("([\\w.]+)\\s+then\\s+([\\w.]+)\\s*->\\s*([\\w.]+\\.\\w+)")
        private val CALL_IDIOM = Regex("([\\w.]+)\\(([^)]*)\\)\\s*->\\s*([\\w.]+\\.\\w+)")
        const val DEFAULT_MAX_RECEIVER_DEPTH = 0
        const val DEFAULT_MAX_SAFE_CALLS = 0
        const val DEFAULT_KDOC_MAX_LINES = 0
        const val DEFAULT_MIN_SAME_TYPE_ARGUMENTS = 3
        const val DEFAULT_FUNCTION_MAX_LINES = 50
        const val DEFAULT_FILE_MAX_LINES = 500
        const val DEFAULT_COMPLEXITY_MAX_SCORE = 15
        const val DEFAULT_COMPLEXITY_HOTSPOT_SHARE = 40
        const val DEFAULT_LITERAL_LOOP_MAX_ELEMENTS = 3
        const val DEFAULT_FILE_MAX_TOP_LEVEL = 15
        val DEFAULT_NO_DATA_CLASS_SCOPE = PublicApiScope.EXPLICIT_API
        const val DEFAULT_COMPOSABLE_MAX_LINES = 80
        val DEFAULT_DELAYS: List<String> = listOf("kotlinx.coroutines.delay", "java.lang.Thread.sleep", "android.os.SystemClock.sleep")
        val DEFAULT_ASYNC_STARTERS: List<String> = listOf(
            "kotlinx.coroutines.launch",
            "kotlinx.coroutines.async",
            "kotlinx.coroutines.flow.launchIn",
            "java.lang.Thread.start",
            "kotlin.concurrent.thread",
            "android.os.Handler.post",
            "android.os.Handler.postDelayed",
            "java.util.concurrent.Executor.execute",
            "java.util.concurrent.ExecutorService.submit",
            "java.util.Timer.schedule",
        )
        val DEFAULT_RESOURCE_FACTORIES: List<String> = listOf(
            "kotlin.io.inputStream", "kotlin.io.outputStream", "kotlin.io.reader", "kotlin.io.writer",
            "kotlin.io.bufferedReader", "kotlin.io.bufferedWriter", "kotlin.io.printWriter", "kotlin.io.buffered",
            "kotlin.io.path.inputStream", "kotlin.io.path.outputStream", "kotlin.io.path.reader", "kotlin.io.path.writer",
            "kotlin.io.path.bufferedReader", "kotlin.io.path.bufferedWriter",
            "java.nio.file.Files.newInputStream", "java.nio.file.Files.newOutputStream", "java.nio.file.Files.newBufferedReader",
            "java.nio.file.Files.newBufferedWriter", "java.nio.file.Files.newDirectoryStream", "java.nio.file.Files.list",
            "java.nio.file.Files.walk", "java.nio.file.Files.lines",
            "java.nio.channels.FileChannel.open", "java.net.ServerSocket.accept",
        )
        /** `AutoCloseable` types that only hold heap memory: closing them frees nothing. */
        val DEFAULT_IN_MEMORY_RESOURCES: List<String> = listOf(
            "java.io.ByteArrayInputStream", "java.io.ByteArrayOutputStream", "java.io.StringReader", "java.io.StringWriter",
            "java.io.CharArrayReader", "java.io.CharArrayWriter", "okio.Buffer",
        )
        val DEFAULT_NATIVE_TYPES: List<String> = listOf("org.jetbrains.skia.impl.Managed", "java.awt.image.VolatileImage")
        val DEFAULT_NATIVE_FACTORIES: List<String> = listOf(
            "java.nio.ByteBuffer.allocateDirect",
            "org.jetbrains.skia.Image.Companion.makeFromEncoded",
            "org.jetbrains.skia.Image.Companion.makeFromBitmap",
            "org.jetbrains.skia.Image.Companion.makeRaster",
            "org.jetbrains.skia.Image.Companion.makeFromPixmap",
            "org.jetbrains.skia.Surface.Companion.makeRaster",
            "org.jetbrains.skia.Surface.Companion.makeRasterN32Premul",
            "org.jetbrains.skia.Bitmap.Companion.makeFromImage",
            "org.jetbrains.skia.Image.encodeToData",
            "org.jetbrains.skia.Bitmap.encodeToData",
        )
        val DEFAULT_PER_ITEM_CALLBACKS: List<String> = listOf(
            "kotlinx.coroutines.flow.collect",
            "kotlinx.coroutines.flow.Flow.collect",
            "kotlinx.coroutines.flow.FlowCollector.emit",
            "kotlinx.coroutines.flow.onEach",
            "kotlinx.coroutines.flow.map",
            "kotlinx.coroutines.flow.transform",
            "androidx.compose.runtime.withFrameNanos",
            "androidx.compose.runtime.withFrameMillis",
            "androidx.compose.runtime.MonotonicFrameClock.withFrameNanos",
            "kotlin.repeat",
            "kotlin.collections.forEach",
            "kotlin.collections.map",
        )
        val DEFAULT_WEAK_TYPES: List<String> = listOf("java.lang.ref.WeakReference", "java.lang.ref.SoftReference", "kotlin.native.ref.WeakReference")
        val DEFAULT_BROAD_CATCH_TYPES: List<String> = listOf(
            "kotlin.Throwable", "kotlin.Exception", "kotlin.RuntimeException",
            "java.lang.Throwable", "java.lang.Exception", "java.lang.RuntimeException", "java.lang.Error",
        )
        val DEFAULT_LOG_FUNCTIONS: List<String> = listOf(
            "kotlin.io.println", "kotlin.io.print", "kotlin.printStackTrace", "kotlin.Throwable.printStackTrace", "java.lang.Throwable.printStackTrace",
            "android.util.Log.*", "java.util.logging.Logger.*", "org.slf4j.Logger.*",
            "io.github.oshai.kotlinlogging.*", "co.touchlab.kermit.*", "timber.log.Timber.*", "com.intellij.openapi.diagnostic.Logger.*",
        )
        val DEFAULT_UNRETAINED_ANNOTATIONS: List<String> = listOf("com.kitakkun.kotrail.lifetime.Unretained")
        val DEFAULT_REMEMBER_KEYS_FUNCTIONS: List<String> = listOf(
            "androidx.compose.runtime.remember",
            "androidx.compose.runtime.saveable.rememberSaveable",
            "androidx.compose.runtime.LaunchedEffect",
            "androidx.compose.runtime.DisposableEffect",
            "androidx.compose.runtime.produceState",
        )
        val DEFAULT_TEST_ASSERTIONS: List<String> = listOf(
            "kotlin.test.*", "org.junit.Assert.*", "org.junit.jupiter.api.Assertions.*", "assertk.*", "io.kotest.*",
            "com.google.common.truth.*", "dev.mokkery.verify*", "io.mockk.verify*", "org.mockito.*verify*",
            "*.assert*", "*.verify*", "*.expect*", "*.should*",
            "*.waitUntil*", "*.captureRoboImage*", "kotlinx.coroutines.withTimeout",
        )
        val DEFAULT_GENERATED_PATHS: List<String> = listOf("*/build/generated/*")
        val DEFAULT_GENERATED_ANNOTATIONS: List<String> = listOf(
            "javax.annotation.processing.Generated",
            "javax.annotation.Generated",
            "jakarta.annotation.Generated",
        )
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

        /** A named predicate is read by the grammar as a bare identifier, which is letters only. */
        private val PREDICATE_NAME = Regex("[A-Za-z]+")

        @OptIn(ExperimentalCompilerApi::class)
        fun from(configuration: CompilerConfiguration): KotrailConfig = try {
            val tree = loadTree(
                configuration.get(KotrailConfigurationKeys.CONFIG_FILE).orEmpty(),
                configuration.get(KotrailConfigurationKeys.OPTIONS).orEmpty(),
            )
            Reader(tree).read().copy(
                fixesDir = configuration.get(KotrailConfigurationKeys.FIXES_DIR),
                composablesDir = configuration.get(KotrailConfigurationKeys.COMPOSABLES_DIR),
                associatedComposablesDirs = configuration.get(KotrailConfigurationKeys.ASSOCIATED_COMPOSABLES_DIRS).orEmpty(),
                unloadableDir = configuration.get(KotrailConfigurationKeys.UNLOADABLE_DIR),
                complexityDir = configuration.get(KotrailConfigurationKeys.COMPLEXITY_DIR),
                bundledUnloadableDirs = configuration.get(KotrailConfigurationKeys.BUNDLED_UNLOADABLE_DIRS).orEmpty(),
                rootDir = configuration.get(KotrailConfigurationKeys.ROOT_DIR),
            )
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
                rejectRedefinedPredicates(tree as ConfigNode.Mapping, parsed)
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
            if (name.startsWith("generated.")) {
                ConfigSchema.GENERATED.firstOrNull { "generated." + it.name == name }?.let { return listOf("generated", it.name) to it.kind }
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
                ConfigSchema.GENERATED.map { "generated." + it.name to it.kind } +
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

        /**
         * A named predicate is one vocabulary for the whole build: a later layer may take one away
         * (`screen: ~`) but not give it another meaning, which would silently change every policy
         * that uses it.
         */
        private fun rejectRedefinedPredicates(base: ConfigNode.Mapping, over: ConfigNode.Mapping) {
            val existing = base["predicates"] as? ConfigNode.Mapping ?: return
            val incoming = over["predicates"] as? ConfigNode.Mapping ?: return
            for ((name, node) in incoming.entries) {
                val earlier = existing[name] ?: continue
                if (node is ConfigNode.Null || earlier is ConfigNode.Null) continue
                throw ConfigException("${incoming.keyAt(name)}: the named predicate '$name' is already defined at ${existing.keyAt(name)}; a shared vocabulary is declared once, and a layer may only unset it with ~")
            }
        }

        /** Rejects unknown keys and wrong shapes with the offending position, before anything is merged. */
        private fun validate(tree: ConfigNode.Mapping) {
            val topNames = ConfigSchema.TOP_LEVEL.map { it.name } + listOf("predicates", "generated", "test", "rules")
            for (key in tree.entries.keys) {
                if (key !in topNames) throw ConfigException("${tree.keyAt(key)}: unknown key '$key'; expected one of ${topNames.joinToString()}")
            }
            tree["predicates"]?.let { predicates ->
                if (predicates is ConfigNode.Null) return@let
                if (predicates !is ConfigNode.Mapping) throw ConfigException("${predicates.at}: predicates must be a mapping of names to predicates")
                for ((name, node) in predicates.entries) {
                    if (!PREDICATE_NAME.matches(name)) throw ConfigException("${predicates.keyAt(name)}: '$name' is not a valid predicate name: letters only, as in viewModel")
                    if (name in ExcludeParser.ATOM_NAMES || name in CallPredicateParser.ATOM_NAMES) throw ConfigException("${predicates.keyAt(name)}: '$name' is a built-in predicate and cannot be redefined")
                    if (node !is ConfigNode.Scalar && node !is ConfigNode.Null) throw ConfigException("${node.at}: the predicate '$name' must be one line, such as composable && name(*Screen)")
                }
            }
            (tree["test"] as? ConfigNode.Mapping)?.let { test ->
                for (key in test.entries.keys) {
                    if (ConfigSchema.TEST.none { it.name == key }) throw ConfigException("${test.keyAt(key)}: unknown key '$key' under test; expected ${ConfigSchema.TEST.joinToString { it.name }}")
                }
            }
            (tree["generated"] as? ConfigNode.Mapping)?.let { generated ->
                for (key in generated.entries.keys) {
                    if (ConfigSchema.GENERATED.none { it.name == key }) throw ConfigException("${generated.keyAt(key)}: unknown key '$key' under generated; expected ${ConfigSchema.GENERATED.joinToString { it.name }}")
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
        /** Splits on commas, except those inside `<...>`, so that `Box<*,_>` stays one entry. */
        fun parseList(value: String): List<String> {
            val items = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            for (c in value) {
                when (c) {
                    '<' -> depth++
                    '>' -> depth--
                    ',' -> if (depth <= 0) {
                        items += current.toString()
                        current.clear()
                        continue
                    }
                }
                current.append(c)
            }
            items += current.toString()
            return items.map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    /** Reads the merged tree into settings, converting and checking every value where it is used. */
    private class Reader(private val tree: ConfigNode.Mapping) {
        private val rules = tree["rules"] as? ConfigNode.Mapping

        /** The project's named predicates, by name; each checked to parse in one of the two predicate languages before anything uses it. */
        private val aliases: Map<String, String> = (tree["predicates"] as? ConfigNode.Mapping)?.entries.orEmpty()
            .mapNotNull { (name, node) -> (node as? ConfigNode.Scalar)?.let { name to it } }
            .also { entries ->
                val texts = entries.associate { (name, node) -> name to node.value }
                for ((name, node) in entries) {
                    if (node.value.isBlank()) fail(node, "the predicate '$name' is empty")
                    // Parsing the name itself expands it under its own name, so a cycle is reported from it.
                    try {
                        ExcludeParser.parse(name, texts)
                    } catch (declaration: ExcludeParser.ExcludeSyntaxException) {
                        try {
                            CallPredicateParser.parse(name, texts)
                        } catch (_: ExcludeParser.ExcludeSyntaxException) {
                            fail(node, declaration.message.orEmpty())
                        }
                    }
                }
            }
            .associate { (name, node) -> name to node.value }

        fun read(): KotrailConfig {
            val switches = KotrailRule.switchable.associateWith { rule -> boolean(ruleNode(rule), "enabled") ?: rule.defaultEnabled }
            // A top-level `severity` is the default for every rule; a rule's own `severity` still wins.
            val projectSeverity = scalar(tree, "severity")?.let { node ->
                listOf("error", "warning").firstOrNull { it.equals(node.value.trim(), ignoreCase = true) }
                    ?.let { parseSeverity(it) }
                    ?: fail(node, "'severity' must be error or warning, got '${node.value}'")
            }
            val severities = KotrailRule.entries.associateWith { rule ->
                enumValue(rule, "severity", listOf("error", "warning"))?.let { parseSeverity(it) } ?: projectSeverity ?: rule.defaultSeverity
            }
            val projectNote = string(tree, "note")
            val notes = KotrailRule.entries.associateWith { rule ->
                val text = string(ruleNode(rule), "note") ?: projectNote
                if (text.isNullOrBlank()) "" else " " + text.trim()
            }
            val projectFix = boolean(tree, "fix") ?: true
            val fixes = KotrailRule.entries.associateWith { rule -> boolean(ruleNode(rule), "fix") ?: projectFix }
            val excludes = KotrailExcludes(
                everywhere = predicate(tree, "exclude"),
                perRule = KotrailRule.entries.mapNotNull { rule -> predicate(ruleNode(rule), "exclude")?.let { rule to it } }.toMap(),
            )
            val test = tree["test"] as? ConfigNode.Mapping
            val generated = tree["generated"] as? ConfigNode.Mapping
            return KotrailConfig(
                enabled = boolean(tree, "enabled") ?: true,
                fixesDir = null,
                composablesDir = null,
                complexityDir = null,
                associatedComposablesDirs = emptyList(),
                unloadableDir = null,
                bundledUnloadableDirs = emptyList(),
                rootDir = null,
                switches = switches,
                severities = severities,
                notes = notes,
                fixes = fixes,
                excludes = excludes,
                compose = KotrailComposeSettings(
                    maxNesting = int(KotrailRule.COMPOSE_NESTING, "maxDepth") ?: DEFAULT_COMPOSE_MAX_NESTING,
                    trailingLambdaAllowedPackages = list(KotrailRule.COMPOSE_NO_TRAILING_CALLBACK, "allowedPackages") ?: DEFAULT_TRAILING_LAMBDA_ALLOWED_PACKAGES,
                    previewRequireFor = enumValue(KotrailRule.COMPOSE_PREVIEW_REQUIRED, "scope", PreviewScope.entries.map { it.key })?.let { PreviewScope.fromKey(it)!! } ?: DEFAULT_PREVIEW_REQUIRE_FOR,
                    nonUiPackages = list(KotrailRule.COMPOSE_PREVIEW_REQUIRED, "nonUiPackages") ?: DEFAULT_NON_UI_PACKAGES,
                    maxComposablesPerFile = int(KotrailRule.COMPOSE_COMPOSABLES_PER_FILE, "max") ?: DEFAULT_MAX_COMPOSABLES_PER_FILE,
                    countOverloadsSeparately = boolean(ruleNode(KotrailRule.COMPOSE_COMPOSABLES_PER_FILE), "countOverloadsSeparately") ?: false,
                    allowComposableSlots = boolean(ruleNode(KotrailRule.COMPOSE_NO_CALLBACK_IN_MODEL), "allowComposableSlots") ?: true,
                    complexity = KotrailComplexity(
                        maxScore = int(KotrailRule.COMPOSE_COMPLEXITY, "maxScore") ?: DEFAULT_COMPLEXITY_MAX_SCORE,
                        hotspotShare = int(KotrailRule.COMPOSE_COMPLEXITY, "hotspotShare") ?: DEFAULT_COMPLEXITY_HOTSPOT_SHARE,
                        stateFactories = list(KotrailRule.COMPOSE_COMPLEXITY, "stateFactories").orEmpty(),
                    ),
                    globalStateHandlerWrites = boolean(ruleNode(KotrailRule.COMPOSE_NO_GLOBAL_MUTABLE_STATE), "handlerWrites") ?: true,
                    previewParameterMinPreviews = int(KotrailRule.COMPOSE_PREVIEW_PARAMETER, "minPreviews") ?: 2,
                    rememberKeysFunctions = list(KotrailRule.COMPOSE_REMEMBER_KEYS, "functions") ?: DEFAULT_REMEMBER_KEYS_FUNCTIONS,
                    sideEffectTypes = list(KotrailRule.COMPOSE_NO_SIDE_EFFECT_IN_COMPOSITION, "types") ?: DEFAULT_SIDE_EFFECT_TYPES,
                    sideEffectFunctions = list(KotrailRule.COMPOSE_NO_SIDE_EFFECT_IN_COMPOSITION, "functions").orEmpty(),
                    hardcodedStringParameters = list(KotrailRule.COMPOSE_NO_HARDCODED_STRING, "parameters") ?: DEFAULT_HARDCODED_STRING_PARAMETERS,
                    previewCoverage = KotrailPreviewCoverage(
                        packages = list(KotrailRule.COMPOSE_PREVIEW_COVERAGE, "packages").orEmpty(),
                        visibility = enumValue(KotrailRule.COMPOSE_PREVIEW_COVERAGE, "visibility", listOf("public", "internal"))?.let { PreviewScope.fromKey(it)!! } ?: PreviewScope.PUBLIC,
                        excludeNames = list(KotrailRule.COMPOSE_PREVIEW_COVERAGE, "excludeNames").orEmpty(),
                    ),
                    stableTypes = list(KotrailRule.COMPOSE_NO_UNSTABLE_PARAMETER, "stableTypes").orEmpty().also { patterns ->
                        patterns.forEach { pattern ->
                            runCatching { StableTypeMatcher(pattern) }.onFailure {
                                fail(KotrailRule.COMPOSE_NO_UNSTABLE_PARAMETER, "stableTypes", "accepts fully qualified names with * and ** wildcards and an optional <*,_> mask, got '$pattern'")
                            }
                        }
                    },
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
                nullChain = KotrailNullChain(
                    maxElvis = int(KotrailRule.NULL_CHAIN_LENGTH, "maxElvis") ?: DEFAULT_MAX_ELVIS,
                    maxSafeCalls = int(KotrailRule.NULL_CHAIN_LENGTH, "maxSafeCalls") ?: DEFAULT_MAX_SAFE_CALLS,
                ),
                liveVariables = KotrailLiveVariables(
                    max = int(KotrailRule.LIVE_VARIABLE_BUDGET, "max") ?: DEFAULT_MAX_LIVE_VARIABLES,
                ),
                narrowLocalScope = KotrailNarrowLocalScope(
                    maxDistance = int(KotrailRule.NARROW_LOCAL_SCOPE, "maxDistance") ?: DEFAULT_LOCAL_MAX_DISTANCE,
                ),
                unloadableCode = KotrailUnloadableCode(
                    registrations = list(KotrailRule.UNLOADABLE_CODE, "registrations") ?: DEFAULT_REGISTRATIONS,
                    disposableTypes = list(KotrailRule.UNLOADABLE_CODE, "disposableTypes") ?: DEFAULT_DISPOSABLE_TYPES,
                ),
                preferIdiom = KotrailPreferIdiom(
                    disabled = list(KotrailRule.PREFER_IDIOM, "disabled").orEmpty().onEach { key ->
                        if (key !in IDIOM_KEYS) fail(KotrailRule.PREFER_IDIOM, "disabled", "accepts ${IDIOM_KEYS.joinToString()}, got '$key'")
                    },
                    chains = list(KotrailRule.PREFER_IDIOM, "chains").orEmpty().map { entry ->
                        CHAIN_IDIOM.matchEntire(entry.trim())?.let { ChainIdiom(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
                            ?: fail(KotrailRule.PREFER_IDIOM, "chains", "entries are '<inner fqn> then <outer fqn> -> <replacement fqn>', got '$entry'")
                    },
                    calls = list(KotrailRule.PREFER_IDIOM, "calls").orEmpty().map { entry ->
                        CALL_IDIOM.matchEntire(entry.trim())?.let { CallIdiom(it.groupValues[1], it.groupValues[2].trim(), it.groupValues[3]) }
                            ?: fail(KotrailRule.PREFER_IDIOM, "calls", "entries are '<fqn>(<literal>) -> <replacement fqn>', got '$entry'")
                    },
                ),
                sealedWhen = KotrailSealedWhen(
                    style = enumValue(KotrailRule.SEALED_WHEN_BRANCH_STYLE, "style", WhenBranchStyle.entries.map { it.key })?.let { WhenBranchStyle.fromKey(it)!! } ?: WhenBranchStyle.IS,
                ),
                implicitReceivers = KotrailImplicitReceivers(
                    qualifyAmbiguous = boolean(ruleNode(KotrailRule.IMPLICIT_RECEIVERS), "qualifyAmbiguous") ?: true,
                    maxDepth = int(KotrailRule.IMPLICIT_RECEIVERS, "maxDepth") ?: DEFAULT_MAX_RECEIVER_DEPTH,
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
                nativeAllocation = KotrailNativeAllocation(
                    types = list(KotrailRule.NATIVE_ALLOCATION_IN_LOOP, "types") ?: DEFAULT_NATIVE_TYPES,
                    factories = list(KotrailRule.NATIVE_ALLOCATION_IN_LOOP, "factories") ?: DEFAULT_NATIVE_FACTORIES,
                    callbacks = list(KotrailRule.NATIVE_ALLOCATION_IN_LOOP, "callbacks") ?: DEFAULT_PER_ITEM_CALLBACKS,
                ),
                mustClose = KotrailMustClose(
                    factories = list(KotrailRule.MUST_CLOSE, "factories") ?: DEFAULT_RESOURCE_FACTORIES,
                    ignoredTypes = list(KotrailRule.MUST_CLOSE, "ignoredTypes") ?: DEFAULT_IN_MEMORY_RESOURCES,
                ),
                objcThrows = KotrailObjCThrows(packages = list(KotrailRule.NATIVE_OBJC_THROWS, "packages").orEmpty().map(::Glob)),
                weakOnlyReference = KotrailWeakOnlyReference(
                    types = list(KotrailRule.WEAK_ONLY_REFERENCE, "types") ?: DEFAULT_WEAK_TYPES,
                ),
                catchTooBroad = KotrailCatchTooBroad(
                    types = list(KotrailRule.CATCH_TOO_BROAD, "types") ?: DEFAULT_BROAD_CATCH_TYPES,
                    report = enumValue(KotrailRule.CATCH_TOO_BROAD, "report", listOf("swallowed", "all")) ?: "swallowed",
                    loggers = list(KotrailRule.CATCH_TOO_BROAD, "loggers") ?: DEFAULT_LOG_FUNCTIONS,
                ),
                unretained = KotrailUnretained(
                    annotations = list(KotrailRule.UNRETAINED, "annotations") ?: DEFAULT_UNRETAINED_ANNOTATIONS,
                    weakTypes = list(KotrailRule.UNRETAINED, "weakTypes") ?: DEFAULT_WEAK_TYPES,
                ),
                asyncWork = KotrailAsyncWork(
                    delays = list(KotrailRule.DELAY_FOR_COMPLETION, "delays") ?: DEFAULT_DELAYS,
                    starters = list(KotrailRule.DELAY_FOR_COMPLETION, "starters") ?: DEFAULT_ASYNC_STARTERS,
                ),
                requiredSupertypes = entries(KotrailRule.REQUIRED_SUPERTYPE, "policies") { node -> node }
                    .map { (name, node) -> policy(name, node, "supertype", "a fully qualified class or interface name").let { (predicate, value) -> KotrailSupertypePolicy(name, predicate, value) } },
                dependencyPolicies = entries(KotrailRule.DEPENDENCY_RULES, "policies") { node -> node }
                    .map { (name, node) -> dependencyPolicy(name, node) },
                generated = KotrailGenerated(
                    paths = (generated?.get("paths")?.let { list(it, "generated.paths") } ?: DEFAULT_GENERATED_PATHS).map(::Glob),
                    annotations = generated?.get("annotations")?.let { list(it, "generated.annotations") } ?: DEFAULT_GENERATED_ANNOTATIONS,
                ),
                test = KotrailTest(
                    annotations = test?.get("annotations")?.let { list(it, "test.annotations") } ?: DEFAULT_TEST_ANNOTATIONS,
                    namingStyle = enumValue(KotrailRule.TEST_NAMING, "style", TestNamingStyle.entries.map { it.key })?.let { TestNamingStyle.fromKey(it)!! } ?: DEFAULT_TEST_NAMING_STYLE,
                    minNameWords = int(KotrailRule.TEST_NAMING, "minWords") ?: DEFAULT_TEST_MIN_NAME_WORDS,
                    assertions = list(KotrailRule.TEST_MUST_ASSERT, "assertions") ?: DEFAULT_TEST_ASSERTIONS,
                    sleepFunctions = list(KotrailRule.TEST_NO_SLEEP, "functions") ?: DEFAULT_TEST_SLEEP_FUNCTIONS,
                    virtualTimeFunctions = list(KotrailRule.TEST_NO_SLEEP, "virtualTime") ?: DEFAULT_TEST_VIRTUAL_TIME_FUNCTIONS,
                ),
                functionLength = KotrailFunctionLength(
                    maxLines = int(KotrailRule.FUNCTION_LENGTH, "maxLines") ?: DEFAULT_FUNCTION_MAX_LINES,
                    maxComposableLines = int(KotrailRule.FUNCTION_LENGTH, "maxComposableLines") ?: DEFAULT_COMPOSABLE_MAX_LINES,
                ),
                literalLoop = KotrailLiteralLoop(maxElements = int(KotrailRule.NO_LITERAL_LOOP, "maxElements") ?: DEFAULT_LITERAL_LOOP_MAX_ELEMENTS),
                fileLength = KotrailFileLength(
                    maxLines = int(KotrailRule.FILE_LENGTH, "maxLines") ?: DEFAULT_FILE_MAX_LINES,
                    maxTopLevelDeclarations = int(KotrailRule.FILE_LENGTH, "maxTopLevelDeclarations") ?: DEFAULT_FILE_MAX_TOP_LEVEL,
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
                ExcludeParser.parse(it.value, aliases)
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
                CallPredicateParser.parse(scalar.value, aliases)
            } catch (e: ExcludeParser.ExcludeSyntaxException) {
                fail(node, e.message.orEmpty())
            }
        }

        /** A policy: `{where: <predicate>, annotation: <fqn>}`, or the one-line `<predicate> -> <fqn>`. */
        /** A `where -> value` policy in either form; returns the predicate and the value under [valueKey]. */
        private fun policy(name: String, node: ConfigNode, valueKey: String, valueWhat: String): Pair<ExcludePredicate, String> {
            val where: String
            val value: String
            when (node) {
                is ConfigNode.Mapping -> {
                    for ((key, child) in node.entries) if (key != "where" && key != valueKey) fail(child, "unknown key '$key' in policy '$name'; expected where and $valueKey")
                    where = string(node, "where") ?: fail(node, "policy '$name' needs 'where', a predicate over declarations")
                    value = string(node, valueKey) ?: fail(node, "policy '$name' needs '$valueKey', $valueWhat")
                }
                is ConfigNode.Scalar -> {
                    val arrow = node.value.lastIndexOf("->")
                    if (arrow < 0) fail(node, "policy '$name' is a mapping with where and $valueKey, or '<predicate> -> <$valueKey>'")
                    where = node.value.substring(0, arrow)
                    value = node.value.substring(arrow + 2).trim()
                }
                else -> fail(node, "policy '$name' is a mapping with where and $valueKey")
            }
            if (value.isEmpty() || value.any { it.isWhitespace() }) fail(node, "'$valueKey' of policy '$name' must be one name, got '$value'")
            val predicate = try {
                ExcludeParser.parse(where, aliases)
            } catch (e: ExcludeParser.ExcludeSyntaxException) {
                fail(node, e.message.orEmpty())
            }
            return predicate to value
        }

        /** A dependency policy: a mapping with `from` (a package glob), `deny` (package globs) and optional `allow`. */
        private fun dependencyPolicy(name: String, node: ConfigNode): KotrailDependencyPolicy {
            val mapping = node as? ConfigNode.Mapping ?: fail(node, "dependency policy '$name' must be a mapping with from, deny and optionally allow")
            for ((key, child) in mapping.entries) if (key !in setOf("from", "deny", "allow")) fail(child, "unknown key '$key' in dependency policy '$name'; expected from, deny, allow")
            val from = string(mapping, "from") ?: fail(node, "dependency policy '$name' needs 'from', a package glob")
            val deny = mapping["deny"]?.let { list(it, "deny") } ?: fail(node, "dependency policy '$name' needs 'deny', package globs")
            val allow = mapping["allow"]?.let { list(it, "allow") }.orEmpty()
            return KotrailDependencyPolicy(name, Glob(from), deny.map(::Glob), allow.map(::Glob))
        }

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
                ExcludeParser.parse(where, aliases)
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

package com.kitakkun.kotrail

import org.jetbrains.kotlin.diagnostics.Severity

/**
 * Every rule (and every independently tunable diagnostic) Kotrail ships.
 *
 * [key] is the suffix used in configuration: `rules.<key>` switches the rule, `severity.<key>`
 * sets its severity. Entries with [hasSwitch] `false` are diagnostics that belong to another
 * rule but carry their own severity (they are switched together with their owner). A rule with
 * [defaultEnabled] `false` only runs when a project switches it on.
 */
enum class KotrailRule(
    val key: String,
    val defaultSeverity: Severity,
    val hasSwitch: Boolean = true,
    val defaultEnabled: Boolean = true,
) {
    PREFER_EXPLICIT_BACKING_FIELD("preferExplicitBackingField", Severity.ERROR),
    PREFER_PRIVATE_SETTER("preferPrivateSetter", Severity.ERROR),
    NARROW_MODEL_PARAMETERS("narrowModelParameters", Severity.ERROR),
    NO_PASS_THROUGH_RETURN("noPassThroughReturn", Severity.ERROR),
    NO_PASS_THROUGH_FUNCTION("noPassThroughFunction", Severity.ERROR),
    PREFER_FUNCTION_REFERENCES("preferFunctionReferences", Severity.ERROR),
    COMMENT_LENGTH("commentLength", Severity.ERROR),
    NO_PARAMETER_COMMENTS("noParameterComments", Severity.ERROR),
    NO_FQN_REFERENCES("noFqnReferences", Severity.ERROR),
    NO_REDUNDANT_ELSE("noRedundantElse", Severity.ERROR),
    PREFER_VALUE_CLASS("preferValueClass", Severity.ERROR),
    FORBIDDEN_CALL("forbiddenCall", Severity.ERROR),
    NO_NOT_NULL_ASSERTION("noNotNullAssertion", Severity.ERROR),
    NO_SWALLOWED_CANCELLATION("noSwallowedCancellation", Severity.ERROR),
    NO_IGNORED_EXCEPTION("noIgnoredException", Severity.ERROR),
    PREFER_EXPRESSION_BODY("preferExpressionBody", Severity.ERROR),
    NO_MUTABLE_COLLECTION_IN_PUBLIC_API("noMutableCollectionInPublicApi", Severity.ERROR),
    NO_DATA_CLASS_IN_PUBLIC_API("noDataClassInPublicApi", Severity.ERROR),
    VISIBILITY_POLICY("visibilityPolicy", Severity.ERROR),
    REQUIRED_ANNOTATION("requiredAnnotation", Severity.ERROR),
    NAMED_ARGUMENTS_FOR_REPEATED_TYPES("namedArgumentsForRepeatedTypes", Severity.ERROR),
    MUST_BE_SERIALIZABLE("mustBeSerializable", Severity.ERROR),
    NO_UNIMPLEMENTED("noUnimplemented", Severity.ERROR),
    PRECONDITIONS("preconditions", Severity.ERROR),
    FUNCTION_LENGTH("functionLength", Severity.ERROR),
    FILE_LENGTH("fileLength", Severity.ERROR),
    NO_LITERAL_LOOP("noLiteralLoop", Severity.ERROR),
    NULL_CHAIN_LENGTH("nullChainLength", Severity.ERROR),
    IMPLICIT_RECEIVERS("implicitReceivers", Severity.ERROR),
    SEALED_WHEN_BRANCH_STYLE("sealedWhenBranchStyle", Severity.ERROR),
    PREFER_VAL("preferVal", Severity.ERROR),
    PREFER_IDIOM("preferIdiom", Severity.ERROR),
    NARROW_LOCAL_SCOPE("narrowLocalScope", Severity.ERROR),
    LIVE_VARIABLE_BUDGET("liveVariableBudget", Severity.ERROR),
    NARRATIVE_ORDER("narrativeOrder", Severity.ERROR),
    PARAMETER_ORDER("parameterOrder", Severity.ERROR),
    /** Off by default: only a JVM library with Java consumers needs `@JvmSynthetic` on its internal API; in an app it is noise. */
    JVM_SYNTHETIC_FOR_INTERNAL("jvmSyntheticForInternal", Severity.ERROR, defaultEnabled = false),
    /** Off by default: only meaningful for a compilation loaded through its own class loader and unloaded later (a host or IDE plugin); a ThreadLocal is fine elsewhere. */
    UNLOADABLE_CODE("unloadableCode", Severity.ERROR, defaultEnabled = false),
    NATIVE_ALLOCATION_IN_LOOP("nativeAllocationInLoop", Severity.ERROR),
    WEAK_ONLY_REFERENCE("weakOnlyReference", Severity.ERROR),
    CATCH_TOO_BROAD("catchTooBroad", Severity.ERROR),
    MUST_CLOSE("mustClose", Severity.ERROR),
    UNRETAINED("unretained", Severity.ERROR),
    REQUIRED_SUPERTYPE("requiredSupertype", Severity.ERROR),
    DEPENDENCY_RULES("dependencyRules", Severity.ERROR),
    DELAY_FOR_COMPLETION("delayForCompletion", Severity.ERROR),
    /** The class-shaped finding of the same rule, a warning: nothing can hide an internal class from Java. */
    JVM_SYNTHETIC_FOR_INTERNAL_CLASS("jvmSyntheticForInternalClass", Severity.WARNING, hasSwitch = false),

    COMPOSE_WINDOW_INSETS("compose.windowInsets", Severity.ERROR),
    COMPOSE_WINDOW_INSETS_UNVERIFIABLE("compose.windowInsetsUnverifiable", Severity.WARNING, hasSwitch = false),
    COMPOSE_WINDOW_INSETS_HANDLED_TWICE("compose.windowInsetsHandledTwice", Severity.WARNING),
    COMPOSE_COMPOSITION_LOCALS("compose.compositionLocals", Severity.ERROR),
    COMPOSE_NESTING("compose.nesting", Severity.ERROR),
    COMPOSE_COMPLEXITY("compose.complexity", Severity.ERROR),
    COMPOSE_STATE_DELEGATION("compose.stateDelegation", Severity.ERROR),
    COMPOSE_NO_TRAILING_CALLBACK("compose.noTrailingCallback", Severity.ERROR),
    COMPOSE_NAMING("compose.naming", Severity.ERROR),
    COMPOSE_MODIFIER_PARAMETER("compose.modifierParameter", Severity.ERROR),
    COMPOSE_NAMED_CALLBACK_ARGUMENTS("compose.namedCallbackArguments", Severity.ERROR),
    COMPOSE_PREVIEW_REQUIRED("compose.previewRequired", Severity.ERROR),
    COMPOSE_COMPOSABLES_PER_FILE("compose.composablesPerFile", Severity.ERROR),
    COMPOSE_NO_SIDE_EFFECT_IN_COMPOSITION("compose.noSideEffectInComposition", Severity.ERROR),
    /** Off by default: only a project that localizes through string resources can act on it; elsewhere every Text("...") would be reported. */
    COMPOSE_NO_HARDCODED_STRING("compose.noHardcodedString", Severity.ERROR, defaultEnabled = false),
    /** Off by default: experimental; the stability inference is a port of the Compose compiler's, not yet proven on large codebases, with known noise. */
    COMPOSE_NO_UNSTABLE_PARAMETER("compose.noUnstableParameter", Severity.ERROR, defaultEnabled = false),
    /** Off by default: needs `packages` to name what must be covered, and is meant for the compilation that carries a library's previews or screenshot tests. */
    COMPOSE_PREVIEW_COVERAGE("compose.previewCoverage", Severity.ERROR, defaultEnabled = false),
    COMPOSE_PREVIEW_PARAMETER("compose.previewParameter", Severity.ERROR),
    COMPOSE_NO_CALLBACK_IN_MODEL("compose.noCallbackInModel", Severity.ERROR),
    COMPOSE_REMEMBER_KEYS("compose.rememberKeys", Severity.ERROR),
    COMPOSE_NO_GLOBAL_MUTABLE_STATE("compose.noGlobalMutableState", Severity.ERROR),

    TEST_NAMING("test.naming", Severity.ERROR),
    TEST_NO_SLEEP("test.noSleep", Severity.ERROR),
    TEST_MUST_ASSERT("test.mustAssert", Severity.ERROR),

    NATIVE_OBJC_IDENTITY("native.objcIdentity", Severity.ERROR),
    NATIVE_OBJC_THROWS("native.objcThrows", Severity.ERROR);

    val switchKey: String get() = "rules.$key"
    val severityKey: String get() = "severity.$key"

    /** Key of the project's own text, appended to this rule's diagnostics. */
    val noteKey: String get() = "note.$key"

    /** Key of the predicate that excludes locations from this rule. */
    val excludeKey: String get() = "exclude.$key"

    companion object {
        val switchable: List<KotrailRule> = entries.filter { it.hasSwitch }
        fun bySwitchKey(key: String): KotrailRule? = switchable.firstOrNull { it.switchKey == key }
        fun bySeverityKey(key: String): KotrailRule? = entries.firstOrNull { it.severityKey == key }
        fun byNoteKey(key: String): KotrailRule? = entries.firstOrNull { it.noteKey == key }
        fun byExcludeKey(key: String): KotrailRule? = entries.firstOrNull { it.excludeKey == key }
    }
}

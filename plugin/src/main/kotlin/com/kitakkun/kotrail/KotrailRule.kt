package com.kitakkun.kotrail

import org.jetbrains.kotlin.diagnostics.Severity

/**
 * Every rule (and every independently tunable diagnostic) Kotrail ships.
 *
 * [key] is the suffix used in configuration: `rules.<key>` switches the rule, `severity.<key>`
 * sets its severity. Entries with [hasSwitch] `false` are diagnostics that belong to another
 * rule but carry their own severity (they are switched together with their owner).
 */
enum class KotrailRule(
    val key: String,
    val defaultSeverity: Severity,
    val hasSwitch: Boolean = true,
) {
    PREFER_EXPLICIT_BACKING_FIELD("preferExplicitBackingField", Severity.ERROR),
    NARROW_MODEL_PARAMETERS("narrowModelParameters", Severity.ERROR),
    NO_PASS_THROUGH_RETURN("noPassThroughReturn", Severity.ERROR),
    PREFER_FUNCTION_REFERENCES("preferFunctionReferences", Severity.ERROR),
    COMMENT_LENGTH("commentLength", Severity.ERROR),
    NO_FQN_REFERENCES("noFqnReferences", Severity.ERROR),
    NO_REDUNDANT_ELSE("noRedundantElse", Severity.ERROR),
    PREFER_VALUE_CLASS("preferValueClass", Severity.ERROR),
    FORBIDDEN_CALL("forbiddenCall", Severity.ERROR),
    NO_NOT_NULL_ASSERTION("noNotNullAssertion", Severity.ERROR),
    NO_SWALLOWED_CANCELLATION("noSwallowedCancellation", Severity.ERROR),
    NO_IGNORED_EXCEPTION("noIgnoredException", Severity.ERROR),
    PREFER_EXPRESSION_BODY("preferExpressionBody", Severity.ERROR),
    NO_MUTABLE_COLLECTION_IN_PUBLIC_API("noMutableCollectionInPublicApi", Severity.ERROR),
    NAMED_ARGUMENTS_FOR_REPEATED_TYPES("namedArgumentsForRepeatedTypes", Severity.ERROR),
    MUST_BE_SERIALIZABLE("mustBeSerializable", Severity.ERROR),
    NO_UNIMPLEMENTED("noUnimplemented", Severity.ERROR),

    COMPOSE_WINDOW_INSETS("compose.windowInsets", Severity.ERROR),
    COMPOSE_WINDOW_INSETS_UNVERIFIABLE("compose.windowInsetsUnverifiable", Severity.WARNING, hasSwitch = false),
    COMPOSE_WINDOW_INSETS_HANDLED_TWICE("compose.windowInsetsHandledTwice", Severity.WARNING),
    COMPOSE_NESTING("compose.nesting", Severity.ERROR),
    COMPOSE_STATE_DELEGATION("compose.stateDelegation", Severity.ERROR),
    COMPOSE_NO_TRAILING_CALLBACK("compose.noTrailingCallback", Severity.ERROR),
    COMPOSE_NAMING("compose.naming", Severity.ERROR),
    COMPOSE_MODIFIER_PARAMETER("compose.modifierParameter", Severity.ERROR),
    COMPOSE_NAMED_CALLBACK_ARGUMENTS("compose.namedCallbackArguments", Severity.ERROR),
    COMPOSE_PREVIEW_REQUIRED("compose.previewRequired", Severity.ERROR),
    COMPOSE_COMPOSABLES_PER_FILE("compose.composablesPerFile", Severity.ERROR);

    val switchKey: String get() = "rules.$key"
    val severityKey: String get() = "severity.$key"

    companion object {
        val switchable: List<KotrailRule> = entries.filter { it.hasSwitch }
        fun bySwitchKey(key: String): KotrailRule? = switchable.firstOrNull { it.switchKey == key }
        fun bySeverityKey(key: String): KotrailRule? = entries.firstOrNull { it.severityKey == key }
    }
}

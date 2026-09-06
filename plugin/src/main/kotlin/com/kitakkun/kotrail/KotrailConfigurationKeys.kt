package com.kitakkun.kotrail

import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.diagnostics.Severity

object KotrailConfigurationKeys {
    /** Path to a `.properties` file with rule settings; individual options override its values. */
    val CONFIG_FILE = CompilerConfigurationKey<String>("configFile")

    val ENABLED = CompilerConfigurationKey<Boolean>(KotrailConfig.KEY_ENABLED)

    val COMPOSE_MAX_NESTING = CompilerConfigurationKey<Int>(KotrailConfig.KEY_COMPOSE_MAX_NESTING)
    val TRAILING_LAMBDA_ALLOWED_PACKAGES = CompilerConfigurationKey<List<String>>(KotrailConfig.KEY_TRAILING_LAMBDA_ALLOWED_PACKAGES)
    val PREVIEW_REQUIRE_FOR = CompilerConfigurationKey<PreviewScope>(KotrailConfig.KEY_PREVIEW_REQUIRE_FOR)
    val MAX_COMPOSABLES_PER_FILE = CompilerConfigurationKey<Int>(KotrailConfig.KEY_MAX_COMPOSABLES_PER_FILE)
    val SERIALIZATION_REQUIRED_FOR = CompilerConfigurationKey<List<String>>(KotrailConfig.KEY_SERIALIZATION_REQUIRED_FOR)
    val NARROW_MODEL_MAX_UNUSED = CompilerConfigurationKey<Int>(KotrailConfig.KEY_NARROW_MODEL_MAX_UNUSED)
    val NARROW_MODEL_SCOPE = CompilerConfigurationKey<NarrowModelParametersScope>(KotrailConfig.KEY_NARROW_MODEL_SCOPE)
    val REFERENCE_FORMS = CompilerConfigurationKey<Set<ReferenceForm>>(KotrailConfig.KEY_REFERENCE_FORMS)
    val COMMENT_MAX_LINES = CompilerConfigurationKey<Int>(KotrailConfig.KEY_COMMENT_MAX_LINES)
    val KDOC_MAX_LINES = CompilerConfigurationKey<Int>(KotrailConfig.KEY_KDOC_MAX_LINES)
    val FQN_ALLOW = CompilerConfigurationKey<List<String>>(KotrailConfig.KEY_FQN_ALLOW)
    val FORBIDDEN_FUNCTIONS = CompilerConfigurationKey<List<String>>(KotrailConfig.KEY_FORBIDDEN_FUNCTIONS)
    val MIN_SAME_TYPE_ARGUMENTS = CompilerConfigurationKey<Int>(KotrailConfig.KEY_MIN_SAME_TYPE_ARGUMENTS)

    private val switchKeys: Map<KotrailRule, CompilerConfigurationKey<Boolean>> =
        KotrailRule.switchable.associateWith { CompilerConfigurationKey<Boolean>(it.switchKey) }
    private val severityKeys: Map<KotrailRule, CompilerConfigurationKey<Severity>> =
        KotrailRule.entries.associateWith { CompilerConfigurationKey<Severity>(it.severityKey) }

    fun switchKey(rule: KotrailRule): CompilerConfigurationKey<Boolean> = switchKeys.getValue(rule)
    fun severityKey(rule: KotrailRule): CompilerConfigurationKey<Severity> = severityKeys.getValue(rule)
}

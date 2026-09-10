package com.kitakkun.kotrail

import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.diagnostics.Severity

object KotrailConfigurationKeys {
    /**
     * Paths to `.properties` files with rule settings, in increasing precedence: a later file
     * overrides the entries of an earlier one, and individual options override them all.
     */
    val CONFIG_FILE = CompilerConfigurationKey<List<String>>("configFile")

    val ENABLED = CompilerConfigurationKey<Boolean>(KotrailConfig.KEY_ENABLED)
    val NOTE = CompilerConfigurationKey<String>(KotrailConfig.KEY_NOTE)

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
    val FUNCTION_MAX_LINES = CompilerConfigurationKey<Int>(KotrailConfig.KEY_FUNCTION_MAX_LINES)
    val COMPOSABLE_MAX_LINES = CompilerConfigurationKey<Int>(KotrailConfig.KEY_COMPOSABLE_MAX_LINES)
    val TEST_ANNOTATIONS = CompilerConfigurationKey<List<String>>(KotrailConfig.KEY_TEST_ANNOTATIONS)
    val TEST_NAMING_STYLE = CompilerConfigurationKey<TestNamingStyle>(KotrailConfig.KEY_TEST_NAMING_STYLE)
    val TEST_MIN_NAME_WORDS = CompilerConfigurationKey<Int>(KotrailConfig.KEY_TEST_MIN_NAME_WORDS)

    private val switchKeys: Map<KotrailRule, CompilerConfigurationKey<Boolean>> =
        KotrailRule.switchable.associateWith { CompilerConfigurationKey<Boolean>(it.switchKey) }
    private val severityKeys: Map<KotrailRule, CompilerConfigurationKey<Severity>> =
        KotrailRule.entries.associateWith { CompilerConfigurationKey<Severity>(it.severityKey) }
    private val noteKeys: Map<KotrailRule, CompilerConfigurationKey<String>> =
        KotrailRule.entries.associateWith { CompilerConfigurationKey<String>(it.noteKey) }

    fun switchKey(rule: KotrailRule): CompilerConfigurationKey<Boolean> = switchKeys.getValue(rule)
    fun severityKey(rule: KotrailRule): CompilerConfigurationKey<Severity> = severityKeys.getValue(rule)
    fun noteKey(rule: KotrailRule): CompilerConfigurationKey<String> = noteKeys.getValue(rule)
}

package com.kitakkun.kotrail.config

import com.kitakkun.kotrail.KotrailRule

/**
 * The shape of a Kotrail configuration, in one place: what the top-level keys are, which
 * settings each rule has and of what kind, and what the reserved per-rule keys mean. The file
 * loader validates against it, the command-line processor declares its options from it, and
 * the JSON Schema for editor completion is generated from it.
 */
object ConfigSchema {
    enum class Kind { BOOLEAN, INT, STRING, PREDICATE, CALL_PREDICATE, ENUM, LIST, ENTRIES }

    /** One setting of a rule. [values] are the choices of an [Kind.ENUM]; [entryHint] describes the value of an [Kind.ENTRIES] map. */
    class Setting(
        val name: String,
        val kind: Kind,
        val description: String,
        val default: String? = null,
        val values: List<String> = emptyList(),
        val entryHint: String? = null,
    )

    /** The keys every rule mapping accepts before its own settings. */
    val RESERVED = listOf(
        Setting("enabled", Kind.BOOLEAN, "Whether the rule runs.", default = "true"),
        Setting("severity", Kind.ENUM, "How a violation is reported.", values = listOf("error", "warning")),
        Setting("note", Kind.STRING, "Text appended to this rule's messages; overrides the top-level note."),
        Setting("exclude", Kind.PREDICATE, "Locations this rule skips, as a predicate over where a diagnostic would be reported."),
    )

    /** The values a rule accepts as a scalar instead of a mapping. */
    val RULE_SHORTHANDS = listOf("on", "off", "error", "warning")

    /** Keys outside `rules`. */
    val TOP_LEVEL = listOf(
        Setting("enabled", Kind.BOOLEAN, "Turns the whole plugin off when false.", default = "true"),
        Setting("note", Kind.STRING, "Text appended to every Kotrail message."),
        Setting("exclude", Kind.PREDICATE, "Locations every rule skips, as a predicate over where a diagnostic would be reported."),
    )

    /** `test.annotations`, shared by the test rules and the `test` predicate, lives under a `test` mapping. */
    val TEST = listOf(
        Setting("annotations", Kind.LIST, "Fully qualified annotations that mark a function as a test; replaces the default list.",
            default = "kotlin.test.Test, org.junit.Test, org.junit.jupiter.api.Test, ..."),
    )

    private val SCOPE_EXPLICIT = listOf("explicitApi", "all")

    val SETTINGS: Map<KotrailRule, List<Setting>> = mapOf(
        KotrailRule.NARROW_MODEL_PARAMETERS to listOf(
            Setting("maxUnusedProperties", Kind.INT, "How many properties of a data-class parameter may stay unread.", default = "3"),
            Setting("scope", Kind.ENUM, "Which functions are inspected.", values = listOf("composables", "all"), default = "composables"),
        ),
        KotrailRule.PREFER_FUNCTION_REFERENCES to listOf(
            Setting("forms", Kind.LIST, "Which reference shapes the rule asks for: topLevel, bound, typeQualified.", default = "topLevel, bound, typeQualified"),
        ),
        KotrailRule.COMMENT_LENGTH to listOf(
            Setting("maxLines", Kind.INT, "Longest allowed block comment or run of consecutive // lines; 0 for unlimited.", default = "5"),
            Setting("maxKDocLines", Kind.INT, "Longest allowed KDoc; 0 for unlimited.", default = "0"),
        ),
        KotrailRule.NO_FQN_REFERENCES to listOf(
            Setting("allow", Kind.LIST, "Package prefixes whose members may be referenced fully qualified."),
        ),
        KotrailRule.FORBIDDEN_CALL to listOf(
            Setting("functions", Kind.LIST, "Fully qualified callables that must not be called."),
            Setting("calls", Kind.ENTRIES, "Named call predicates; matching calls are reported under the entry's name.",
                entryHint = "a call predicate, e.g. fqn(kotlinx.coroutines.launch) && receiver(kotlinx.coroutines.GlobalScope)"),
        ),
        KotrailRule.NAMED_ARGUMENTS_FOR_REPEATED_TYPES to listOf(
            Setting("minArguments", Kind.INT, "How many positional arguments of one type require names.", default = "3"),
        ),
        KotrailRule.MUST_BE_SERIALIZABLE to listOf(
            Setting("requiredFor", Kind.LIST, "Callables whose type arguments must be serializable; replaces the default list.",
                default = "androidx.compose.runtime.saveable.rememberSerializable"),
        ),
        KotrailRule.FUNCTION_LENGTH to listOf(
            Setting("maxLines", Kind.INT, "Most lines of code a function body may have; 0 for unlimited.", default = "50"),
            Setting("maxComposableLines", Kind.INT, "The same limit for @Composable functions.", default = "80"),
        ),
        KotrailRule.NO_DATA_CLASS_IN_PUBLIC_API to listOf(
            Setting("scope", Kind.ENUM, "explicitApi applies the rule only to modules compiled with explicit API mode; all everywhere.", values = SCOPE_EXPLICIT, default = "explicitApi"),
        ),
        KotrailRule.VISIBILITY_POLICY to listOf(
            Setting("private", Kind.PREDICATE, "Declarations that must be private."),
            Setting("internal", Kind.PREDICATE, "Declarations that must be internal or private."),
        ),
        KotrailRule.REQUIRED_ANNOTATION to listOf(
            Setting("policies", Kind.ENTRIES, "Named policies; matching declarations must carry the annotation.",
                entryHint = "a mapping with where (a predicate) and annotation (a fully qualified name)"),
        ),
        KotrailRule.COMPOSE_WINDOW_INSETS to listOf(
            Setting("known", Kind.ENTRIES, "What a library composable handles, keyed by its fully qualified name.",
                entryHint = "Type or Type:Side+Side entries, or none"),
        ),
        KotrailRule.COMPOSE_COMPOSITION_LOCALS to listOf(
            Setting("platform", Kind.LIST, "Locals the platform provides at every root; reads of these are never reported."),
            Setting("required", Kind.LIST, "Locals to treat as required although their default does not throw."),
            Setting("roots", Kind.LIST, "Functions whose composable lambda is a root of composition; replaces the default list.",
                default = "androidx.activity.compose.setContent, androidx.compose.ui.window.Window, ..."),
            Setting("known", Kind.ENTRIES, "What a library composable reads and provides, keyed by its fully qualified name.",
                entryHint = "a mapping with reads (a list of locals) and provides (parameter name to a list of locals), or none"),
        ),
        KotrailRule.NULL_CHAIN_LENGTH to listOf(
            Setting("maxElvis", Kind.INT, "Maximum ?: fallbacks in one expression, a trailing ?: return or ?: throw not counted; 0 disables.", default = "2"),
            Setting("maxSafeCalls", Kind.INT, "Maximum ?. along one receiver chain; 0 disables.", default = "0"),
        ),
        KotrailRule.SEALED_WHEN_BRANCH_STYLE to listOf(
            Setting("style", Kind.ENUM, "How an object case of a sealed type is written in a when branch: is (`is Cancel ->`) or object (`Cancel ->`).", values = listOf("is", "object"), default = "is"),
        ),
        KotrailRule.IMPLICIT_RECEIVERS to listOf(
            Setting("qualifyOuter", Kind.BOOLEAN, "Whether a bare name resolved on an outer implicit receiver, past a nearer one, is reported.", default = "true"),
            Setting("maxDepth", Kind.INT, "Maximum implicit receivers in scope at once; 0 disables.", default = "0"),
        ),
        KotrailRule.COMPOSE_NESTING to listOf(
            Setting("maxDepth", Kind.INT, "Nesting limit for composable calls; 0 disables the rule.", default = "5"),
        ),
        KotrailRule.COMPOSE_NO_TRAILING_CALLBACK to listOf(
            Setting("allowedPackages", Kind.LIST, "Packages whose composables may still take a callback as a trailing lambda.", default = "androidx.compose.runtime"),
        ),
        KotrailRule.COMPOSE_PREVIEW_REQUIRED to listOf(
            Setting("scope", Kind.ENUM, "Which UI composables need a @Preview in their file.", values = listOf("public", "internal", "all"), default = "internal"),
        ),
        KotrailRule.COMPOSE_COMPOSABLES_PER_FILE to listOf(
            Setting("max", Kind.INT, "Maximum non-private UI composables in one file, previews excluded; 0 disables.", default = "3"),
            Setting("countOverloadsSeparately", Kind.BOOLEAN, "Whether overloads of one composable name count one each; by default they count as one component.", default = "false"),
        ),
        KotrailRule.COMPOSE_NO_SIDE_EFFECT_IN_COMPOSITION to listOf(
            Setting("types", Kind.LIST, "Fully qualified return types whose producers start work when called in a composable body; replaces the default list.", default = "kotlinx.coroutines.Job, kotlinx.coroutines.Deferred"),
            Setting("functions", Kind.LIST, "Fully qualified functions that start work when called in a composable body, in addition to those recognized by type."),
        ),
        KotrailRule.COMPOSE_NO_UNSTABLE_PARAMETER to listOf(
            Setting("stableTypes", Kind.LIST, "Types the project declares stable, as fully qualified names with * and ** wildcards and an optional <*,_> mask of the type arguments that count, like a Compose stability configuration file."),
        ),
        KotrailRule.COMPOSE_PREVIEW_COVERAGE to listOf(
            Setting("packages", Kind.LIST, "Exact package names whose top-level UI composables must be called by a @Preview function somewhere in this compilation."),
            Setting("visibility", Kind.ENUM, "Which composables of those packages count.", values = listOf("public", "internal"), default = "public"),
            Setting("excludeNames", Kind.LIST, "Globs over fully qualified composable names to leave out."),
        ),
        KotrailRule.COMPOSE_NO_HARDCODED_STRING to listOf(
            Setting("parameters", Kind.LIST, "Names of composable parameters that must not receive a string literal; replaces the default list.", default = "text, label, title, placeholder, contentDescription, message"),
        ),
        KotrailRule.TEST_NAMING to listOf(
            Setting("style", Kind.ENUM, "backticked for a sentence name, identifier for targets that reject spaces.", values = listOf("backticked", "identifier"), default = "backticked"),
            Setting("minWords", Kind.INT, "Words a backticked test name must have; 1 accepts any name.", default = "3"),
        ),
        KotrailRule.TEST_NO_SLEEP to listOf(
            Setting("functions", Kind.LIST, "Fully qualified functions that wait real time; replaces the default list.", default = "java.lang.Thread.sleep, android.os.SystemClock.sleep, java.util.concurrent.TimeUnit.sleep"),
            Setting("virtualTime", Kind.LIST, "Fully qualified functions whose lambda runs on virtual time, where delay is free; replaces the default list.", default = "kotlinx.coroutines.test.runTest"),
        ),
    )

    fun settingsOf(rule: KotrailRule): List<Setting> = SETTINGS[rule].orEmpty()

    /** The rule whose key is [key], or `null`. */
    fun ruleByKey(key: String): KotrailRule? = KotrailRule.entries.firstOrNull { it.key == key }

    /** The JSON Schema (draft 7) of a configuration file, for editor completion and validation. */
    fun jsonSchema(): String {
        val sb = StringBuilder()
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun setting(s: Setting): String {
            val desc = q(s.description + (s.default?.let { " Default: $it." } ?: ""))
            return when (s.kind) {
                Kind.BOOLEAN -> "{\"type\": \"boolean\", \"description\": $desc}"
                Kind.INT -> "{\"type\": \"integer\", \"minimum\": 0, \"description\": $desc}"
                Kind.STRING, Kind.PREDICATE, Kind.CALL_PREDICATE -> "{\"type\": \"string\", \"description\": $desc}"
                Kind.ENUM -> "{\"type\": \"string\", \"enum\": [${s.values.joinToString { q(it) }}], \"description\": $desc}"
                Kind.LIST -> "{\"type\": \"array\", \"items\": {\"type\": \"string\"}, \"description\": $desc}"
                Kind.ENTRIES -> "{\"type\": \"object\", \"additionalProperties\": true, \"description\": ${q(s.description + " Each value: " + s.entryHint + ".")}}"
            }
        }
        val rules = KotrailRule.entries.joinToString(",\n") { rule ->
            val reserved = RESERVED.mapNotNull {
                when {
                    it.name != "enabled" -> it
                    !rule.hasSwitch -> null
                    else -> Setting(it.name, it.kind, it.description, default = rule.defaultEnabled.toString())
                }
            }
            val props = reserved + settingsOf(rule)
            val shorthands = if (rule.hasSwitch) RULE_SHORTHANDS else listOf("error", "warning")
            "    ${q(rule.key)}: {\"oneOf\": [{\"type\": \"string\", \"enum\": [${shorthands.joinToString { q(it) }}]}, " +
                "{\"type\": \"object\", \"additionalProperties\": false, \"properties\": {" +
                props.joinToString(", ") { "${q(it.name)}: ${setting(it)}" } + "}}]}"
        }
        sb.append("{\n")
        sb.append("  \"\$schema\": \"http://json-schema.org/draft-07/schema#\",\n")
        sb.append("  \"\$id\": \"https://kitakkun.github.io/kotrail/kotrail.schema.json\",\n")
        sb.append("  \"title\": \"Kotrail configuration\",\n")
        sb.append("  \"type\": \"object\",\n  \"additionalProperties\": false,\n  \"properties\": {\n")
        sb.append(TOP_LEVEL.joinToString(",\n") { "    ${q(it.name)}: ${setting(it)}" })
        sb.append(",\n    \"test\": {\"type\": \"object\", \"additionalProperties\": false, \"properties\": {")
        sb.append(TEST.joinToString(", ") { "${q(it.name)}: ${setting(it)}" })
        sb.append("}},\n    \"rules\": {\"type\": \"object\", \"additionalProperties\": false, \"properties\": {\n")
        sb.append(rules.prependIndent("  "))
        sb.append("\n    }}\n  }\n}\n")
        return sb.toString()
    }
}

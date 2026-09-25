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
        Setting("fix", Kind.BOOLEAN, "Whether this rule's fixes are recorded for kotrailFix; false keeps the diagnostic and leaves the edit to the author. Overrides the top-level fix.", default = "true"),
    )

    /** The values a rule accepts as a scalar instead of a mapping. */
    val RULE_SHORTHANDS = listOf("on", "off", "error", "warning")

    /** Keys outside `rules`. */
    val TOP_LEVEL = listOf(
        Setting("enabled", Kind.BOOLEAN, "Turns the whole plugin off when false.", default = "true"),
        Setting("severity", Kind.ENUM, "The severity of every rule that does not set its own; error unless set.", values = listOf("error", "warning")),
        Setting("note", Kind.STRING, "Text appended to every Kotrail message."),
        Setting("exclude", Kind.PREDICATE, "Locations every rule skips, as a predicate over where a diagnostic would be reported."),
        Setting("fix", Kind.BOOLEAN, "Whether fixes are recorded for kotrailFix at all; false turns the task into a no-op until a rule says fix: true.", default = "true"),
    )

    /** What counts as generated code, which every rule skips; under a `generated` mapping. */
    val GENERATED = listOf(
        Setting("paths", Kind.LIST, "Globs over source file paths (with / separators) of generated code, which every rule skips; replaces the default.", default = "*/build/generated/*"),
        Setting("annotations", Kind.LIST, "Fully qualified annotations that mark a file or declaration as generated, which every rule skips; replaces the default list.",
            default = "javax.annotation.processing.Generated, javax.annotation.Generated, jakarta.annotation.Generated"),
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
        KotrailRule.LIVE_VARIABLE_BUDGET to listOf(
            Setting("max", Kind.INT, "Variables (locals and parameters) that may be live at one statement of a function; 0 disables.", default = "7"),
        ),
        KotrailRule.UNLOADABLE_CODE to listOf(
            Setting("registrations", Kind.LIST, "Globs over fully qualified functions that register something with the platform for the rest of its life; a call without a disposable argument is reported. Replaces the default list of JVM, AWT and IntelliJ registrations.", default = "java.lang.Runtime.addShutdownHook, java.lang.Thread.setDefaultUncaughtExceptionHandler, java.awt.Toolkit.addAWTEventListener, java.awt.KeyboardFocusManager.addPropertyChangeListener, java.awt.KeyboardFocusManager.addKeyEventDispatcher, com.intellij.util.messages.MessageBus.connect, com.intellij.openapi.application.Application.addApplicationListener, com.intellij.openapi.extensions.ExtensionPointName.addExtensionPointListener, com.intellij.openapi.extensions.ExtensionPointName.addChangeListener, com.intellij.openapi.editor.EditorFactory.addEditorFactoryListener, com.intellij.openapi.vfs.VirtualFileManager.addVirtualFileListener, com.intellij.openapi.project.ProjectManager.addProjectManagerListener"),
            Setting("disposableTypes", Kind.LIST, "Fully qualified types an argument of which scopes a registration to a lifetime. Replaces the default list.", default = "com.intellij.openapi.Disposable"),
        ),
        KotrailRule.NATIVE_ALLOCATION_IN_LOOP to listOf(
            Setting("types", Kind.LIST, "Fully qualified types (subtypes included) whose instances hold native memory that only a cleaner frees; replaces the default list.", default = "org.jetbrains.skia.Managed, java.awt.image.VolatileImage"),
            Setting("factories", Kind.LIST, "Fully qualified factory functions that return such an instance; replaces the default list.", default = "java.nio.ByteBuffer.allocateDirect"),
            Setting("callbacks", Kind.LIST, "Fully qualified functions whose lambda runs once per item or frame, counted like a loop body; replaces the default list.", default = "kotlinx.coroutines.flow.collect, kotlinx.coroutines.flow.onEach, androidx.compose.runtime.withFrameNanos, kotlin.repeat, kotlin.collections.forEach, ..."),
        ),
        KotrailRule.WEAK_ONLY_REFERENCE to listOf(
            Setting("types", Kind.LIST, "Fully qualified weak or soft reference types (subtypes included); replaces the default list.", default = "java.lang.ref.WeakReference, java.lang.ref.SoftReference, kotlin.native.ref.WeakReference"),
        ),
        KotrailRule.CATCH_TOO_BROAD to listOf(
            Setting("types", Kind.LIST, "Fully qualified exception types a catch clause must not name; replaces the default list.", default = "kotlin.Throwable, kotlin.Exception, kotlin.RuntimeException, java.lang.Throwable, java.lang.Exception, java.lang.RuntimeException, java.lang.Error"),
        ),
        KotrailRule.UNRETAINED to listOf(
            Setting("annotations", Kind.LIST, "Fully qualified annotations that mark a parameter as not to be retained; replaces the default list.", default = "com.kitakkun.kotrail.lifetime.Unretained"),
            Setting("weakTypes", Kind.LIST, "Fully qualified weak reference types (subtypes included) through which such a parameter may be kept; replaces the default list.", default = "java.lang.ref.WeakReference, java.lang.ref.SoftReference, kotlin.native.ref.WeakReference"),
        ),
        KotrailRule.REQUIRED_SUPERTYPE to listOf(
            Setting("policies", Kind.ENTRIES, "Named policies; matching classes must extend or implement the supertype.",
                entryHint = "a mapping with where (a predicate) and supertype (a fully qualified name), or '<predicate> -> <fqn>'"),
        ),
        KotrailRule.DEPENDENCY_RULES to listOf(
            Setting("policies", Kind.ENTRIES, "Named policies; code in packages matching from must not refer to packages matching deny, unless they match allow.",
                entryHint = "a mapping with from (a package glob), deny (package globs) and optionally allow (package globs)"),
        ),
        KotrailRule.NARROW_LOCAL_SCOPE to listOf(
            Setting("maxDistance", Kind.INT, "Lines allowed between a local's declaration and the statement that first uses it; 0 switches the distance check off.", default = "5"),
        ),
        KotrailRule.PREFER_IDIOM to listOf(
            Setting("disabled", Kind.LIST, "Idioms not asked for: emptiness (size == 0), negation (!isEmpty()), nullOrEmpty (x == null || x.isEmpty()), chain (filter { }.first()), elvis (if (x != null) x else y)."),
            Setting("chains", Kind.LIST, "The project's own chain idioms, each '<inner fqn> then <outer fqn> -> <replacement fqn>': inner(arg).outer() is written as the replacement with arg."),
            Setting("calls", Kind.LIST, "The project's own call idioms, each '<fqn>(<literal>) -> <replacement fqn>': a call with that one literal argument is written as the replacement with none."),
        ),
        KotrailRule.SEALED_WHEN_BRANCH_STYLE to listOf(
            Setting("style", Kind.ENUM, "How an object case of a sealed type is written in a when branch: is (`is Cancel ->`) or object (`Cancel ->`).", values = listOf("is", "object"), default = "is"),
        ),
        KotrailRule.IMPLICIT_RECEIVERS to listOf(
            Setting("qualifyAmbiguous", Kind.BOOLEAN, "Whether a bare name that two implicit receivers in scope could supply is reported.", default = "true"),
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
            Setting("nonUiPackages", Kind.LIST, "Packages whose composables emit nothing themselves (effects, providers), so that calling them does not make a composable a UI one; shared with previewCoverage. Replaces the default list.", default = "androidx.compose.runtime"),
        ),
        KotrailRule.COMPOSE_PREVIEW_PARAMETER to listOf(
            Setting("minPreviews", Kind.INT, "How many @Preview functions of one file must build the same model inline before they are reported; 1 reports every one.", default = "2"),
        ),
        KotrailRule.COMPOSE_REMEMBER_KEYS to listOf(
            Setting("functions", Kind.LIST, "Fully qualified functions whose trailing lambda is keyed by their other arguments; replaces the default list.", default = "androidx.compose.runtime.remember, androidx.compose.runtime.saveable.rememberSaveable, androidx.compose.runtime.LaunchedEffect, androidx.compose.runtime.DisposableEffect, androidx.compose.runtime.produceState"),
        ),
        KotrailRule.COMPOSE_NO_CALLBACK_IN_MODEL to listOf(
            Setting("allowComposableSlots", Kind.BOOLEAN, "Whether a @Composable function-typed property (a content slot such as a table column's cell renderer) in a model handed to a UI composable is allowed; other function types are always reported.", default = "true"),
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
        KotrailRule.TEST_MUST_ASSERT to listOf(
            Setting("assertions", Kind.LIST, "Globs over fully qualified functions that assert or verify; a test that calls none of them, directly or through its helpers, is reported. Replaces the default list.", default = "kotlin.test.*, org.junit.Assert.*, org.junit.jupiter.api.Assertions.*, assertk.*, io.kotest.*, com.google.common.truth.*, dev.mokkery.verify*, io.mockk.verify*, org.mockito.*verify*, *.assert*, *.verify*, *.expect*, *.should*"),
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
        sb.append(",\n    \"generated\": {\"type\": \"object\", \"additionalProperties\": false, \"properties\": {")
        sb.append(GENERATED.joinToString(", ") { "${q(it.name)}: ${setting(it)}" })
        sb.append("}},\n    \"test\": {\"type\": \"object\", \"additionalProperties\": false, \"properties\": {")
        sb.append(TEST.joinToString(", ") { "${q(it.name)}: ${setting(it)}" })
        sb.append("}},\n    \"rules\": {\"type\": \"object\", \"additionalProperties\": false, \"properties\": {\n")
        sb.append(rules.prependIndent("  "))
        sb.append("\n    }}\n  }\n}\n")
        return sb.toString()
    }
}

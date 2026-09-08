package com.kitakkun.kotrail.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/** Severity a rule's diagnostics are reported at. */
enum class KotrailSeverity(internal val key: String) {
    ERROR("error"),
    WARNING("warning"),
}

/**
 * Settings that can be given to the plugin, either for the whole project or for one compilation.
 *
 * Rule keys (`compose.nesting`, `preferExplicitBackingField`, …) and setting keys
 * (`compose.maxNesting`, `test.annotations`, …) are the ones listed in `docs/configuration.md`.
 * They are passed to the compiler plugin as written, which reports an unknown key as a build
 * failure rather than ignoring it.
 */
abstract class KotrailOptions {
    /** Whether the plugin runs at all. Default `true`. */
    abstract val enabled: Property<Boolean>

    /**
     * A `.properties` file with rule settings. Anything set through this DSL takes precedence
     * over the file, exactly as an explicit plugin option does on the command line.
     */
    abstract val configFile: RegularFileProperty

    /** `rules.<key>` switches. Prefer [disable] and [enable]. */
    abstract val rules: MapProperty<String, Boolean>

    /** `severity.<key>` overrides. Prefer [warning] and [severity]. */
    abstract val severities: MapProperty<String, KotrailSeverity>

    /** Rule settings, keyed as in the properties file. Prefer [setting]. */
    abstract val settings: MapProperty<String, String>

    /** Switches the named rules off. */
    fun disable(vararg ruleKeys: String) {
        ruleKeys.forEach { rules.put(it, false) }
    }

    /** Switches the named rules on, for a compilation where they were switched off. */
    fun enable(vararg ruleKeys: String) {
        ruleKeys.forEach { rules.put(it, true) }
    }

    /** Reports the named rules as warnings instead of errors. */
    fun warning(vararg ruleKeys: String) {
        ruleKeys.forEach { severities.put(it, KotrailSeverity.WARNING) }
    }

    /** Reports one rule at [severity]. */
    fun severity(ruleKey: String, severity: KotrailSeverity) {
        severities.put(ruleKey, severity)
    }

    fun setting(key: String, value: String) {
        settings.put(key, value)
    }

    fun setting(key: String, value: Int) {
        settings.put(key, value.toString())
    }

    /** A list-valued setting, joined the way the compiler plugin expects. */
    fun setting(key: String, values: Iterable<String>) {
        settings.put(key, values.joinToString(","))
    }
}

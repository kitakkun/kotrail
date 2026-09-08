package com.kitakkun.kotrail.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Applies the Kotrail compiler plugin to every Kotlin compilation of the project and forwards the
 * `kotrail { }` block to it as plugin options.
 *
 * The compiler plugin links against the Kotlin compiler's internal API, so it is published once
 * per supported Kotlin version, with the Kotlin version leading the artifact version
 * (`kotrail-compiler-plugin:2.4.0-0.1.0`). This Gradle plugin is version independent: it reads the
 * Kotlin version the project applies and asks for the matching artifact.
 */
class KotrailGradlePlugin : KotlinCompilerPluginSupportPlugin {
    private lateinit var project: Project
    private lateinit var extension: KotrailExtension

    override fun apply(target: Project) {
        project = target
        extension = target.extensions.create(EXTENSION_NAME, KotrailExtension::class.java).apply {
            enabled.convention(true)
            annotations.convention(true)
        }
        addAnnotationsDependency(target)
    }

    override fun getCompilerPluginId(): String = COMPILER_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(GROUP, COMPILER_PLUGIN_ARTIFACT, compilerPluginVersion())

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        specsFor(kotlinCompilation.name).lastOrNull { it.enabled.isPresent }?.enabled?.get() ?: true

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val compilationName = kotlinCompilation.name
        return project.provider { optionsFor(compilationName) }
    }

    /**
     * The project's own settings first, then every override whose compilation-name predicate
     * matches, so that a later entry wins over an earlier one.
     */
    private fun specsFor(compilationName: String): List<KotrailOptions> =
        listOf<KotrailOptions>(extension) +
            extension.overrides.filter { it.matches(compilationName) }.map { it.options }

    private fun optionsFor(compilationName: String): List<SubpluginOption> {
        val specs = specsFor(compilationName)
        val options = mutableListOf<SubpluginOption>()

        specs.lastOrNull { it.enabled.isPresent }?.let {
            options += SubpluginOption("enabled", it.enabled.get().toString())
        }
        specs.lastOrNull { it.configFile.isPresent }?.let {
            options += SubpluginOption("configFile", it.configFile.get().asFile.absolutePath)
        }

        val ruleSwitches = LinkedHashMap<String, Boolean>()
        val ruleSeverities = LinkedHashMap<String, KotrailSeverity>()
        val ruleSettings = LinkedHashMap<String, String>()
        for (spec in specs) {
            ruleSwitches.putAll(spec.rules.get())
            ruleSeverities.putAll(spec.severities.get())
            ruleSettings.putAll(spec.settings.get())
        }
        ruleSwitches.forEach { (rule, value) -> options += SubpluginOption("rules.$rule", value.toString()) }
        ruleSeverities.forEach { (rule, value) -> options += SubpluginOption("severity.$rule", value.key) }
        ruleSettings.forEach { (setting, value) -> options += SubpluginOption(setting, value) }
        return options
    }

    /**
     * The artifact version, pairing the Kotlin version the project applies with the Kotrail
     * version. The `kotrail.compilerPluginVersion` property overrides it, for a locally published
     * build.
     */
    private fun compilerPluginVersion(): String =
        project.providers.gradleProperty(COMPILER_PLUGIN_VERSION_PROPERTY).orNull
            ?: "${kotlinVersion()}-$KOTRAIL_VERSION"

    private fun kotlinVersion(): String =
        project.plugins.filterIsInstance<KotlinBasePlugin>().firstOrNull()?.pluginVersion
            ?: error(
                "Kotrail found no Kotlin Gradle plugin in ${project.path}. Apply a Kotlin plugin " +
                    "(for example `kotlin(\"jvm\")`) before `com.kitakkun.kotrail`.",
            )

    /**
     * The annotations the rules recognize are an ordinary library, so a project needs them on its
     * compile classpath to write `@HandlesWindowInsets` or `@MustBeSerializable`. Multiplatform
     * projects add the artifact to the source sets that need it themselves.
     */
    private fun addAnnotationsDependency(target: Project) {
        for (pluginId in listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.android")) {
            target.pluginManager.withPlugin(pluginId) {
                target.afterEvaluate {
                    if (extension.annotations.get()) {
                        target.dependencies.add("implementation", "$GROUP:$ANNOTATIONS_ARTIFACT:$KOTRAIL_VERSION")
                    }
                }
            }
        }
    }

    private companion object {
        const val EXTENSION_NAME = "kotrail"

        /** Must match `KotrailNames.PLUGIN_ID` in the compiler plugin. */
        const val COMPILER_PLUGIN_ID = "com.kitakkun.kotrail"

        const val GROUP = "com.kitakkun.kotrail"
        const val COMPILER_PLUGIN_ARTIFACT = "kotrail-compiler-plugin"
        const val ANNOTATIONS_ARTIFACT = "kotrail-annotations"
        const val COMPILER_PLUGIN_VERSION_PROPERTY = "kotrail.compilerPluginVersion"
    }
}

package com.kitakkun.kotrail.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.plugin.FilesOptionKind
import org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File

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
        target.tasks.register(FIX_TASK, KotrailFixTask::class.java) { task ->
            task.group = "verification"
            task.description = "Applies the fixes Kotrail recorded during the last compilation of each source file"
            task.fixesDirectory.set(fixesDirectory())
        }
    }

    override fun getCompilerPluginId(): String = COMPILER_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(GROUP, COMPILER_PLUGIN_ARTIFACT, compilerPluginVersion())

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        specsFor(kotlinCompilation.name).lastOrNull { it.enabled.isPresent }?.enabled?.get() ?: true

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val compilationName = kotlinCompilation.name
        registerConfigFilesAsInputs(kotlinCompilation, compilationName)
        wireFixRecords(kotlinCompilation)
        wireComposableRecords(kotlinCompilation)
        wireUnloadableRecords(kotlinCompilation)
        return project.provider {
            val options = optionsFor(compilationName) +
                SubpluginOption("fixesDir", fixesDirectoryFor(kotlinCompilation).get().asFile.path) +
                SubpluginOption("composablesDir", composablesDirectoryFor(kotlinCompilation).get().asFile.path) +
                kotlinCompilation.allAssociatedCompilations.map {
                    SubpluginOption("associatedComposablesDir", composablesDirectoryFor(it).get().asFile.path)
                } +
                SubpluginOption("unloadableDir", unloadableDirectoryFor(kotlinCompilation).get().asFile.path) +
                bundledProjects(kotlinCompilation).map {
                    SubpluginOption("bundledUnloadableDir", it.layout.buildDirectory.dir("kotrail/unloadable").get().asFile.path)
                }
            // `--info` shows what each compilation was handed, for a consumer to check its wiring.
            project.logger.info("Kotrail: options for ${kotlinCompilation.compileKotlinTaskName}: ${options.joinToString { "${it.key}=${it.value}" }}")
            options
        }
    }

    /**
     * The option value is the file's absolute path, which says nothing about its contents, so
     * editing a configuration file would otherwise leave the compilation up to date and the new
     * settings unapplied until something else changed. The files are resolved through a provider,
     * as the options are, so that a `configFile` set after this point (from `afterEvaluate`, a
     * convention plugin, an init script) reaches the task's inputs and the compiler alike;
     * otherwise a build could be cached under one file's key and compiled with another.
     */
    private fun registerConfigFilesAsInputs(kotlinCompilation: KotlinCompilation<*>, compilationName: String) {
        kotlinCompilation.compileTaskProvider.configure { task ->
            task.inputs.files(project.provider { configFilesFor(compilationName) })
                .withPropertyName("kotrailConfigFiles")
                .withPathSensitivity(PathSensitivity.RELATIVE)
                .optional()
        }
    }

    /**
     * Every compilation records the fixes of its findings under `build/kotrail/fixes`, one file per
     * source file with the source's content hash, so that `kotrailFix` can apply them without
     * compiling again. The directory is an output of the compile task: it is restored from the
     * build cache with the classes and removed by `clean`.
     */
    private fun wireFixRecords(kotlinCompilation: KotlinCompilation<*>) {
        val directory = fixesDirectoryFor(kotlinCompilation)
        kotlinCompilation.compileTaskProvider.configure { task ->
            task.outputs.dir(directory).withPropertyName("kotrailFixes")
        }
    }

    /**
     * Every compilation records the UI composables it declares under `build/kotrail/composables`,
     * and reads the records of the compilations it is associated with (`main`, for a `test` or a
     * `preview` compilation), so that `compose.previewCoverage` in the compilation that carries
     * the previews can check `main`, which it otherwise sees as class files only. The records are
     * an output of the compile task and an input of the associated compilations' compile tasks.
     */
    private fun wireComposableRecords(kotlinCompilation: KotlinCompilation<*>) {
        val directory = composablesDirectoryFor(kotlinCompilation)
        kotlinCompilation.compileTaskProvider.configure { task ->
            task.outputs.dir(directory).withPropertyName("kotrailComposables")
            task.inputs.files(project.provider { kotlinCompilation.allAssociatedCompilations.map { composablesDirectoryFor(it).get() } })
                .withPropertyName("kotrailAssociatedComposables")
                .withPathSensitivity(PathSensitivity.RELATIVE)
                .optional()
        }
    }

    /**
     * Every compilation records the outbound references the unloadableCode rule looks for under
     * `build/kotrail/unloadable`, and a compilation with the rule on reads the records of the
     * projects on its runtime class path: a plugin that is unloaded with its class loader bundles
     * them, so one switch on the plugin module covers whatever it bundles.
     */
    private fun wireUnloadableRecords(kotlinCompilation: KotlinCompilation<*>) {
        val directory = unloadableDirectoryFor(kotlinCompilation)
        kotlinCompilation.compileTaskProvider.configure { task ->
            task.outputs.dir(directory).withPropertyName("kotrailUnloadable")
            task.inputs.files(project.provider { bundledProjects(kotlinCompilation).map { it.layout.buildDirectory.dir("kotrail/unloadable").get() } })
                .withPropertyName("kotrailBundledUnloadable")
                .withPathSensitivity(PathSensitivity.RELATIVE)
                .optional()
        }
    }

    /** The projects on the compilation's runtime class path, transitively; those whose classes the compilation's artifact bundles. */
    private fun bundledProjects(kotlinCompilation: KotlinCompilation<*>): List<Project> {
        val name = kotlinCompilation.runtimeDependencyConfigurationName ?: return emptyList()
        val configuration = project.configurations.findByName(name) ?: return emptyList()
        return configuration.incoming.resolutionResult.allComponents
            .mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }
            .filter { it != project.path }
            .distinct()
            .sorted()
            .map { project.project(it) }
    }

    private fun unloadableDirectoryFor(kotlinCompilation: KotlinCompilation<*>): Provider<Directory> =
        project.layout.buildDirectory.dir("kotrail/unloadable/${kotlinCompilation.directoryName()}")

    private fun composablesDirectoryFor(kotlinCompilation: KotlinCompilation<*>): Provider<Directory> =
        project.layout.buildDirectory.dir("kotrail/composables/${kotlinCompilation.directoryName()}")

    private fun fixesDirectory(): Provider<Directory> = project.layout.buildDirectory.dir("kotrail/fixes")

    private fun fixesDirectoryFor(kotlinCompilation: KotlinCompilation<*>): Provider<Directory> =
        fixesDirectory().map { it.dir(kotlinCompilation.directoryName()) }

    /** `jvm-main`, `iosArm64-test`; the target of a plain Kotlin/JVM project has no name and is called `jvm`. */
    private fun KotlinCompilation<*>.directoryName(): String = "${target.name.ifEmpty { "jvm" }}-$name"

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
        // Config files are layered, not replaced: the compiler plugin reads them in the order they
        // are passed and a later file overrides the entries of an earlier one, so an override's
        // file only lists what it changes. They travel as FilesSubpluginOption so that the
        // absolute paths stay out of the task's input fingerprint; their contents are registered
        // as inputs separately.
        configFilesFor(compilationName).forEach {
            options += FilesSubpluginOption("configFile", listOf(it), FilesOptionKind.INTERNAL)
        }
        return options
    }

    private fun configFilesFor(compilationName: String): List<File> =
        specsFor(compilationName).filter { it.configFile.isPresent }.map { it.configFile.get().asFile }

    /**
     * The artifact version, pairing the Kotlin version the project applies with the Kotrail
     * version. The `kotrail.compilerPluginVersion` property overrides it, for a locally published
     * build.
     */
    private fun compilerPluginVersion(): String {
        project.providers.gradleProperty(COMPILER_PLUGIN_VERSION_PROPERTY).orNull?.let { return it }
        val kotlinVersion = kotlinVersion()
        checkKotlinVersionIsSupported(kotlinVersion)
        return "$kotlinVersion-$KOTRAIL_VERSION"
    }

    private fun kotlinVersion(): String =
        project.plugins.filterIsInstance<KotlinBasePlugin>().firstOrNull()?.pluginVersion
            ?: error(
                "Kotrail found no Kotlin Gradle plugin in ${project.path}. Apply a Kotlin plugin " +
                    "(for example `kotlin(\"jvm\")`) before `com.kitakkun.kotrail`.",
            )

    /**
     * The annotations the rules recognize are an ordinary library, so a project needs them on its
     * compile classpath to write `@HandlesWindowInsets` or `@MustBeSerializable`. They have binary
     * retention and nothing reads them at runtime, so they are `compileOnly`: a library that
     * applies Kotrail does not put Kotrail into its published dependencies. Multiplatform projects
     * add the artifact to the source sets that need it themselves.
     */
    private fun addAnnotationsDependency(target: Project) {
        for (pluginId in listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.android")) {
            target.pluginManager.withPlugin(pluginId) {
                target.afterEvaluate {
                    if (extension.annotations.get()) {
                        target.dependencies.add("compileOnly", "$GROUP:$ANNOTATIONS_ARTIFACT:$KOTRAIL_VERSION")
                    }
                }
            }
        }
    }

    private companion object {
        const val EXTENSION_NAME = "kotrail"
        const val FIX_TASK = "kotrailFix"

        /** Must match `KotrailNames.PLUGIN_ID` in the compiler plugin. */
        const val COMPILER_PLUGIN_ID = "com.kitakkun.kotrail"

        const val GROUP = "com.kitakkun.kotrail"
        const val COMPILER_PLUGIN_ARTIFACT = "kotrail-compiler-plugin"
        const val ANNOTATIONS_ARTIFACT = "kotrail-annotations"
        const val COMPILER_PLUGIN_VERSION_PROPERTY = "kotrail.compilerPluginVersion"
    }
}

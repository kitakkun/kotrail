package com.kitakkun.kotrail.gradle

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.plugin.FilesOptionKind
import org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetsContainer
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
    private lateinit var factsFiles: FactsFiles
    private lateinit var publishing: Provider<Boolean>

    override fun apply(target: Project) {
        project = target
        extension = target.extensions.create(EXTENSION_NAME, KotrailExtension::class.java).apply {
            enabled.convention(true)
            annotations.convention(true)
            consumerRules.convention(true)
            inferredMetadata.convention(true)
            incrementalMetadata.convention(true)
            packagingExcludes.convention(true)
            publishingTasks.convention(PublishingBuild.DEFAULT_TASK_NAMES)
        }
        publishing = PublishingBuild.of(target, extension.publishingTasks)
        factsFiles = FactsFiles(target) { compilationName ->
            specsFor(compilationName).lastOrNull { it.inferredMetadata.isPresent }?.inferredMetadata?.get() ?: true
        }
        factsFiles.registerAttributeSchema()
        addAnnotationsDependency(target)
        addConsumerRules(target)
        addPackagingExcludes(target)
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
        wireComplexityRecords(kotlinCompilation)
        wireUnloadableRecords(kotlinCompilation)
        // The facts file this compilation writes and those of its dependencies, and whether the facts also go into
        // the class files' Kotlin metadata for incremental compilation (JVM and Android only).
        val factsOptions = factsFiles.wire(kotlinCompilation)?.zip(incrementalMetadataOption(compilationName), FactsFiles.ConcatOptions())
        val options = project.provider {
            val options = optionsFor(compilationName) +
                SubpluginOption("fixesDir", fixesDirectoryFor(kotlinCompilation).get().asFile.path) +
                SubpluginOption("composablesDir", composablesDirectoryFor(kotlinCompilation).get().asFile.path) +
                SubpluginOption("complexityDir", complexityDirectoryFor(kotlinCompilation).get().asFile.path) +
                kotlinCompilation.allAssociatedCompilations.map {
                    SubpluginOption("associatedComposablesDir", composablesDirectoryFor(it).get().asFile.path)
                } +
                SubpluginOption("rootDir", project.rootDir.path) +
                SubpluginOption("unloadableDir", unloadableDirectoryFor(kotlinCompilation).get().asFile.path) +
                bundledRecordDirectories(kotlinCompilation).map { SubpluginOption("bundledUnloadableDir", it.path) }
            // `--info` shows what each compilation was handed, for a consumer to check its wiring.
            project.logger.info("Kotrail: options for ${kotlinCompilation.compileKotlinTaskName}: ${options.joinToString { "${it.key}=${it.value}" }}")
            options
        }
        return if (factsOptions == null) options else options.zip(factsOptions, FactsFiles.ConcatOptions())
    }

    /**
     * `icMetadata`: on unless `incrementalMetadata` (or `inferredMetadata`, which writes no
     * metadata at all) is off for the compilation, or the build publishes to a repository
     * ([PublishingBuild], `publishingTasks`), so that class files published there carry no
     * Kotrail metadata. A plain option, so an input of the compile task: when it flips, the task
     * runs again and compiles every file, since Gradle reports no incremental change for a changed
     * input that is not a file. Read when the options are, after the task graph is ready.
     */
    private fun incrementalMetadataOption(compilationName: String): Provider<List<SubpluginOption>> {
        val publishing = publishing
        return project.provider {
            val specs = specsFor(compilationName)
            val incremental = specs.lastOrNull { it.incrementalMetadata.isPresent }?.incrementalMetadata?.get() ?: true
            val inferred = specs.lastOrNull { it.inferredMetadata.isPresent }?.inferredMetadata?.get() ?: true
            // Constant for a compilation that writes none, so that publishing does not compile it again for nothing.
            val on = incremental && inferred && !publishing.get()
            listOf(SubpluginOption(IC_METADATA_OPTION, on.toString()))
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
            // Everything below is settled while the task is configured, as plain files: the configuration cache
            // then has nothing of the plugin or the project to serialize with the task.
            // The artifacts of the bundled configurations: their project dependencies' compile tasks then run first,
            // so that their records exist when this task reads them.
            task.inputs.files(bundledConfigurations(kotlinCompilation))
                .withPropertyName("kotrailBundledConfigurations")
                .withPathSensitivity(PathSensitivity.RELATIVE)
                .optional()
            task.inputs.files(bundledRecordDirectories(kotlinCompilation))
                .withPropertyName("kotrailBundledUnloadable")
                .withPathSensitivity(PathSensitivity.RELATIVE)
                .optional()
        }
    }

    /** The configurations named under `bundledConfigurations` for this compilation, across the project's settings and its overrides. */
    private fun bundledConfigurations(kotlinCompilation: KotlinCompilation<*>): List<Configuration> =
        specsFor(kotlinCompilation.name)
            .flatMap { it.bundledConfigurations.getOrElse(emptyList()) }
            .distinct()
            .map { name ->
                project.configurations.findByName(name)
                    ?: error("Kotrail: bundledConfigurations names '$name', which is not a configuration of ${project.path}.")
            }

    /**
     * The `*-main` record directories of the bundled projects for the targets of the consumer's
     * platform, and only those: the compile task of a consumer depends on the main compilation
     * of a dependency for its own platform through its classes, so those records exist by the
     * time the task runs, while a `test` or `preview` compilation, or the main compilation of
     * another target of a multiplatform dependency, is not a task the consumer depends on, and
     * its records must not be an input. The targets are read from each bundled project's Kotlin
     * extension, so the set is known while the task is configured. An Android consumer of a
     * library with no Android target gets the library's JVM variant, and its JVM records. A
     * common (metadata) compilation bundles nothing.
     */
    private fun bundledRecordDirectories(kotlinCompilation: KotlinCompilation<*>): List<File> {
        if (kotlinCompilation.platformType == KotlinPlatformType.common) return emptyList()
        val wanted = platformKey(kotlinCompilation.target)
        val fallback = if (kotlinCompilation.platformType == KotlinPlatformType.androidJvm) KotlinPlatformType.jvm.name else null
        return bundledProjects(kotlinCompilation).flatMap { bundled ->
            val targets = bundled.kotlinTargets()
            val matching = targets.filter { platformKey(it) == wanted }.ifEmpty { targets.filter { fallback != null && platformKey(it) == fallback } }
            matching.map { bundled.layout.buildDirectory.dir("kotrail/unloadable/${it.name.ifEmpty { "jvm" }}-main").get().asFile }
        }
    }

    /** The Kotlin targets a project declares: one for a JVM or Android project, several for a multiplatform one, none without Kotlin. */
    private fun Project.kotlinTargets(): List<KotlinTarget> = when (val extension = extensions.findByName("kotlin")) {
        is KotlinTargetsContainer -> extension.targets.toList()
        is KotlinJvmExtension -> listOf(extension.target)
        is KotlinAndroidExtension -> listOf(extension.target)
        else -> emptyList()
    }

    /** A target's platform for matching records: the platform type, and the target's name for the types that have several. */
    private fun platformKey(target: KotlinTarget): String = when (val type = target.platformType) {
        KotlinPlatformType.native, KotlinPlatformType.wasm -> "${type.name}:${target.name}"
        else -> type.name
    }

    /**
     * The projects whose classes the compilation's artifact bundles: those on its runtime class
     * path, and those of the configurations named under `bundledConfigurations`, transitively.
     */
    private fun bundledProjects(kotlinCompilation: KotlinCompilation<*>): List<Project> {
        val runtime = kotlinCompilation.runtimeDependencyConfigurationName?.let { project.configurations.findByName(it) }
        val configurations = listOfNotNull(runtime) + bundledConfigurations(kotlinCompilation)
        return configurations
            .flatMap { it.incoming.resolutionResult.allComponents }
            .mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }
            .filter { it != project.path }
            .distinct()
            .sorted()
            .map { project.project(it) }
    }

    private fun unloadableDirectoryFor(kotlinCompilation: KotlinCompilation<*>): Provider<Directory> =
        project.layout.buildDirectory.dir("kotrail/unloadable/${kotlinCompilation.directoryName()}")

    /** The complexity records are an output of the compile task, for the kotrailComplexity report to read. */
    private fun wireComplexityRecords(kotlinCompilation: KotlinCompilation<*>) {
        val directory = complexityDirectoryFor(kotlinCompilation)
        kotlinCompilation.compileTaskProvider.configure { task ->
            task.outputs.dir(directory).withPropertyName("kotrailComplexity")
        }
    }

    private fun complexityDirectoryFor(kotlinCompilation: KotlinCompilation<*>): Provider<Directory> =
        project.layout.buildDirectory.dir("kotrail/complexity/${kotlinCompilation.directoryName()}")

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
        specs.lastOrNull { it.inferredMetadata.isPresent }?.let {
            options += SubpluginOption("inferredMetadata", it.inferredMetadata.get().toString())
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
        // For the annotations a project writes by hand. The metadata the compiler plugin writes for other
        // modules does not need the artifact (see InferredAnnotations.kt in the compiler plugin), so a
        // multiplatform project, where compileOnly is not supported on the non-JVM targets, is left alone.
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

    /**
     * The inferred-fact annotations that earlier versions wrote into JVM class files reference
     * classes a consumer without Kotrail does not have. The JVM ignores them at runtime, but R8 and
     * ProGuard report every missing class a program class references, and R8 fails the build on
     * it. The JVM output of this version keeps the facts in a facts file outside the JAR and references
     * nothing of Kotrail; every module still ships a `-dontwarn` rule for its consumers (see
     * [ConsumerRules]), for the libraries of earlier versions an app may also shrink, written by
     * one task:
     *
     * - A Kotlin/JVM module, and every JVM target of a multiplatform one, gets the task's output
     *   as a resource directory of its main compilation, so the JAR carries
     *   `META-INF/proguard/kotrail.pro`, which R8 reads from every dependency JAR. Not the
     *   `androidJvm` target of a multiplatform module: its resources end up in the AAR's
     *   `classes.jar`, where they are not consumer rules.
     * - An Android library (`com.android.library`) gets the file in
     *   `defaultConfig.consumerProguardFiles`, which the AAR ships as its `proguard.txt`.
     * - A multiplatform Android library (`com.android.kotlin.multiplatform.library`) gets it in
     *   `optimization.consumerKeepRules` of its `android { }` DSL, with `publish` on, which the
     *   AAR ships the same way.
     *
     * The plugin does not compile against the Android Gradle plugin, so both Android routes go
     * through `androidComponents.finalizeDsl`, by reflection. Both Android plugins register that
     * extension, and it runs the callback after the build script, when `consumerRules` is known,
     * and before the DSL is locked. The tasks that read the consumer files
     * (`export<Variant>ConsumerProguardFiles`, `merge<Variant>ConsumerProguardFiles`, which skip a
     * file that does not exist) depend on the task that writes it.
     */
    private fun addConsumerRules(target: Project) {
        // A fixed location, not derived from the task: the Android DSL needs the file during
        // configuration, and a task-output provider must not be read before the task has run.
        val outputDirectory = target.layout.buildDirectory.dir(CONSUMER_RULES_DIRECTORY)
        val task = target.tasks.register(CONSUMER_RULES_TASK, KotrailConsumerRulesTask::class.java) { task ->
            task.description = "Writes the shrinker rules this module's consumers need for the inferred-fact annotations"
            task.writeRules.set(extension.consumerRules)
            task.outputDirectory.set(outputDirectory)
        }
        // The JVM resource directories take the task output: Gradle resolves it lazily and infers the dependency.
        val directory = task.flatMap { it.outputDirectory }
        target.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            target.pluginManager.withPlugin("java") {
                target.extensions.getByType(SourceSetContainer::class.java)
                    .named(SourceSet.MAIN_SOURCE_SET_NAME) { it.resources.srcDir(directory) }
            }
        }
        target.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            val kotlin = target.extensions.getByName("kotlin") as KotlinTargetsContainer
            kotlin.targets.configureEach { kotlinTarget ->
                // Only `jvm`: an `androidJvm` target's resources are not consumer rules (see above).
                if (kotlinTarget.platformType != KotlinPlatformType.jvm) return@configureEach
                kotlinTarget.compilations.configureEach { compilation ->
                    if (compilation.name == KotlinCompilation.MAIN_COMPILATION_NAME) compilation.defaultSourceSet.resources.srcDir(directory)
                }
            }
        }
        // A layout provider without a task behind it, safe to read in `finalizeDsl`; the explicit
        // dependencies of `dependOnConsumerRules` make the task write the file before AGP reads it.
        val rulesFile = outputDirectory.map { it.file(ConsumerRules.RESOURCE_PATH).asFile }
        target.pluginManager.withPlugin("com.android.library") {
            finalizeAndroidDsl(target, extension.consumerRules, CONSUMER_RULES_SKIPPED) { android ->
                val defaultConfig = android.javaClass.getMethod("getDefaultConfig").invoke(android)
                defaultConfig.javaClass.getMethod("consumerProguardFile", Any::class.java).invoke(defaultConfig, rulesFile.get())
            }
            dependOnConsumerRules(target, task)
        }
        target.pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
            finalizeAndroidDsl(target, extension.consumerRules, CONSUMER_RULES_SKIPPED) { android ->
                val missing = addConsumerKeepRules(android, rulesFile.get())
                if (missing != null) {
                    target.logger.warn("Kotrail: found no $missing on the Android DSL of ${target.path}; $CONSUMER_RULES_SKIPPED.")
                }
            }
            dependOnConsumerRules(target, task)
        }
    }

    private fun dependOnConsumerRules(target: Project, task: TaskProvider<KotrailConsumerRulesTask>) {
        target.tasks.configureEach {
            if (it.name.startsWith("export") || it.name.startsWith("merge")) {
                if (it.name.endsWith("ConsumerProguardFiles")) it.dependsOn(task)
            }
        }
    }

    /**
     * `androidComponents.finalizeDsl { if (enabled) configure(it) }`, through reflection. [skipped]
     * names what is left out when the Android plugin has no such extension, for the warning.
     */
    private fun finalizeAndroidDsl(target: Project, enabled: Provider<Boolean>, skipped: String, configure: (android: Any) -> Unit) {
        val components = target.extensions.findByName("androidComponents")
        val finalizeDsl = components?.javaClass?.methods?.firstOrNull {
            it.name == "finalizeDsl" && it.parameterTypes.singleOrNull() == Action::class.java
        }
        if (components == null || finalizeDsl == null) {
            target.logger.warn("Kotrail: found no androidComponents.finalizeDsl in ${target.path}; $skipped.")
            return
        }
        val callback = Action<Any> { android ->
            if (enabled.get()) configure(android)
        }
        finalizeDsl.invoke(components, callback)
    }

    /**
     * Libraries built by earlier versions of Kotrail carry their facts file in the JAR or AAR, as
     * `META-INF/kotrail/<module>.facts`, and an Android application would copy it into its APK as
     * a Java resource, where nothing reads it. `androidComponents.finalizeDsl {
     * it.packaging.resources.excludes += FactsFilePackaging.PATTERN }`, through
     * [finalizeAndroidDsl] like [addConsumerRules], unless `packagingExcludes` is off. Libraries
     * built by this version publish the facts file as an artifact of its own (see [FactsFiles]),
     * so this only matters for the older ones.
     */
    private fun addPackagingExcludes(target: Project) {
        target.pluginManager.withPlugin("com.android.application") {
            finalizeAndroidDsl(target, extension.packagingExcludes, PACKAGING_EXCLUDES_SKIPPED) { android ->
                val missing = FactsFilePackaging.exclude(android)
                if (missing != null) {
                    target.logger.warn("Kotrail: found no $missing in the Android DSL of ${target.path}; $PACKAGING_EXCLUDES_SKIPPED.")
                }
            }
        }
    }

    private companion object {
        const val EXTENSION_NAME = "kotrail"
        const val IC_METADATA_OPTION = "icMetadata"
        const val FIX_TASK = "kotrailFix"
        const val CONSUMER_RULES_TASK = "kotrailConsumerRules"
        const val CONSUMER_RULES_DIRECTORY = "kotrail/consumer-rules"
        const val CONSUMER_RULES_SKIPPED = "consumer shrinker rules are not added"
        const val PACKAGING_EXCLUDES_SKIPPED = "the facts files of older libraries are not excluded from packaging"

        /** Must match `KotrailNames.PLUGIN_ID` in the compiler plugin. */
        const val COMPILER_PLUGIN_ID = "com.kitakkun.kotrail"

        const val GROUP = "com.kitakkun.kotrail"
        const val COMPILER_PLUGIN_ARTIFACT = "kotrail-compiler-plugin"
        const val ANNOTATIONS_ARTIFACT = "kotrail-annotations"
        const val COMPILER_PLUGIN_VERSION_PROPERTY = "kotrail.compilerPluginVersion"
    }
}

/** Leaves the facts files that libraries built by earlier versions carry out of an Android application's packaging. */
internal object FactsFilePackaging {
    /** What an application excludes: every facts file in a dependency's JAR, whichever module wrote it. */
    const val PATTERN: String = "META-INF/kotrail/**"

    /**
     * Adds [PATTERN] to `packaging.resources.excludes` of [android], an Android DSL extension
     * reached by reflection. Returns null when it did, or the member it could not find.
     */
    fun exclude(android: Any): String? {
        val packaging = call(android, "getPackaging") ?: return "packaging"
        val resources = call(packaging, "getResources") ?: return "packaging.resources"
        @Suppress("UNCHECKED_CAST")
        val excludes = call(resources, "getExcludes") as? MutableSet<String> ?: return "packaging.resources.excludes"
        excludes.add(PATTERN)
        return null
    }

    private fun call(receiver: Any, getter: String): Any? =
        receiver.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }?.invoke(receiver)
}

/**
 * `android.optimization.consumerKeepRules.run { publish = true; file(rulesFile) }` on the
 * `KotlinMultiplatformAndroidLibraryExtension` [android], through reflection. Returns the member
 * that was not found, or null when the rules were added.
 */
internal fun addConsumerKeepRules(android: Any, rulesFile: File): String? {
    val optimization = android.javaClass.methods.firstOrNull { it.name == "getOptimization" && it.parameterCount == 0 }
        ?.invoke(android) ?: return "optimization"
    val consumerKeepRules = optimization.javaClass.methods.firstOrNull { it.name == "getConsumerKeepRules" && it.parameterCount == 0 }
        ?.invoke(optimization) ?: return "optimization.consumerKeepRules"
    val setPublish = consumerKeepRules.javaClass.methods.firstOrNull {
        it.name == "setPublish" && it.parameterTypes.singleOrNull() == Boolean::class.javaPrimitiveType
    } ?: return "optimization.consumerKeepRules.publish"
    val file = consumerKeepRules.javaClass.methods.firstOrNull {
        it.name == "file" && it.parameterTypes.singleOrNull() == Any::class.java
    } ?: return "optimization.consumerKeepRules.file(Any)"
    setPublish.invoke(consumerKeepRules, true)
    file.invoke(consumerKeepRules, rulesFile)
    return null
}

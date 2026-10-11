package com.kitakkun.kotrail.gradle

import com.kitakkun.kotrail.gradle.FactsFiles.Companion.configurationName
import com.kitakkun.kotrail.gradle.FactsFiles.Companion.directoryName
import com.kitakkun.kotrail.gradle.FactsFiles.Companion.isJvmLike
import com.kitakkun.kotrail.gradle.FactsFiles.Companion.isProducing
import org.gradle.api.Project
import org.gradle.api.Transformer
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Usage
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.plugin.FilesOptionKind
import org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.util.concurrent.Callable

/**
 * The records of the unloadableCode rule. Every compilation records the outbound references the
 * rule looks for under `build/kotrail/unloadable/<target>-<compilation>` (`unloadableDir`),
 * whether or not the rule is on there, and a JVM or Android compilation reads the records of the
 * projects its artifact bundles into its class loader (`bundledUnloadableDir`), for the rule to
 * report them from the module that is unloaded:
 *
 * - **Producer.** The record directory is an output of the compile task. That of a main
 *   compilation (`main`, or an Android variant; never a test or preview compilation) is also the
 *   artifact of a consumable configuration (`kotrailUnloadableElements`,
 *   `jvmKotrailUnloadableElements`, `releaseKotrailUnloadableElements`), built by the compile
 *   task. It carries the platform and compilation attributes of the facts variant ([FactsFiles])
 *   and a usage of its own, which no Java or Kotlin consumer asks for. No software component is
 *   given it, so it is never published and no module metadata describes it.
 * - **Consumer.** A JVM or Android compilation reads the record directories of the projects on
 *   its runtime class path (unless `bundlesRuntimeClasspath` is off) and on the configurations
 *   named under `bundledConfigurations`, transitively: an artifact view of each, with variant
 *   reselection and limited to projects, asks every project for that variant, and a project
 *   without Kotrail gives nothing. The platform is matched as for the facts: a multiplatform
 *   dependency gives the records of its target for the consumer's platform, and an Android
 *   consumer of a library without an Android target gets the library's JVM records. A common
 *   (metadata) compilation, or one of another platform, bundles nothing. The directories are an
 *   input of the compile task, so the compile tasks that write them run first, for an
 *   `implementation` and a `runtimeOnly` dependency alike.
 */
internal class UnloadableRecords(
    private val project: Project,
    /** The configurations named under `bundledConfigurations` for the compilation of this name. */
    private val bundledConfigurations: (String) -> List<Configuration>,
    /** Whether the compilation of this name bundles its runtime class path (the `bundlesRuntimeClasspath` setting). */
    private val bundlesRuntimeClasspath: (String) -> Boolean,
) {
    /** Wires the records of [compilation] and returns the options that tell the compiler where they are. */
    fun wire(compilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val directory = directoryFor(compilation)
        if (compilation.isJvmLike() && compilation.isProducing()) registerElements(compilation, directory)
        val bundled = bundledRecords(compilation)
        compilation.compileTaskProvider.configure { task ->
            task.outputs.dir(directory).withPropertyName("kotrailUnloadable")
            task.inputs.files(bundled)
                .withPropertyName("kotrailBundledUnloadable")
                .withPathSensitivity(PathSensitivity.RELATIVE)
                .optional()
        }
        val own = project.provider { listOf(SubpluginOption(DIRECTORY_OPTION, directory.get().asFile.path)) }
        // BundledOptions and ConcatOptions are classes rather than lambdas so that the
        // configuration cache can store them with the provider.
        return own.zip(bundled.elements.map(BundledOptions()), FactsFiles.ConcatOptions())
    }

    private fun directoryFor(compilation: KotlinCompilation<*>): Provider<Directory> =
        project.layout.buildDirectory.dir("kotrail/unloadable/${compilation.directoryName()}")

    /** The consumable configuration whose artifact is the record directory of a main compilation. */
    private fun registerElements(compilation: KotlinCompilation<*>, directory: Provider<Directory>) {
        project.configurations.create(compilation.configurationName(ELEMENTS_SUFFIX)) { configuration ->
            configuration.description = "The unloadable-code records of the ${compilation.name} compilation of ${project.path}, for the Kotrail plugin of the modules that bundle it"
            configuration.isCanBeConsumed = true
            configuration.isCanBeResolved = false
            configuration.attributes.setRecordsAttributes(compilation.platformType.name, compilation.name)
            configuration.outgoing.artifact(directory) { artifact ->
                artifact.type = TYPE
                artifact.builtBy(compilation.compileTaskProvider)
            }
        }
    }

    /**
     * The record directories of the projects [compilation] bundles. The views are made when the
     * compile task's inputs are first asked for, by which time the build script has named the
     * bundled configurations.
     */
    private fun bundledRecords(compilation: KotlinCompilation<*>): FileCollection {
        if (!compilation.isJvmLike()) return project.files()
        val views = lazy {
            val runtime = compilation.runtimeDependencyConfigurationName
                ?.takeIf { bundlesRuntimeClasspath(compilation.name) }
                ?.let { project.configurations.findByName(it) }
            (listOfNotNull(runtime) + bundledConfigurations(compilation.name)).distinct().map { recordsOf(it, compilation) }
        }
        return project.files(Callable { views.value })
    }

    private fun recordsOf(configuration: Configuration, compilation: KotlinCompilation<*>): FileCollection {
        val platform = compilation.platformType.name
        val compilationName = compilation.name
        val ownPath = project.path
        return configuration.incoming.artifactView { view ->
            view.withVariantReselection()
            view.lenient(true)
            view.componentFilter { it is ProjectComponentIdentifier && it.projectPath != ownPath }
            view.attributes { it.setRecordsAttributes(platform, compilationName) }
        }.files
    }

    private fun AttributeContainer.setRecordsAttributes(platform: String, compilationName: String) {
        attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, USAGE))
        attribute(FactsFiles.PLATFORM_ATTRIBUTE, platform)
        attribute(FactsFiles.COMPILATION_ATTRIBUTE, compilationName)
    }

    /** One `bundledUnloadableDir` option per record directory among [locations] that exists. */
    class BundledOptions : Transformer<List<SubpluginOption>, Set<FileSystemLocation>> {
        override fun transform(locations: Set<FileSystemLocation>): List<SubpluginOption> {
            val directories = locations.map { it.asFile }.filter { it.isDirectory }.distinctBy { it.absolutePath }.sortedBy { it.absolutePath }
            Logging.getLogger(UnloadableRecords::class.java).info("Kotrail: unloadable records read: ${directories.joinToString { it.path }.ifEmpty { "none" }}")
            // Files options keep the absolute paths out of the compile task's input fingerprint;
            // the directories are registered as an input of their own.
            return directories.map { FilesSubpluginOption(BUNDLED_OPTION, listOf(it), FilesOptionKind.INTERNAL) }
        }
    }

    companion object {
        /** The usage of the records variant, which no Java or Kotlin consumer requests. */
        const val USAGE = "kotrail-unloadable"

        const val DIRECTORY_OPTION = "unloadableDir"
        const val BUNDLED_OPTION = "bundledUnloadableDir"

        private const val TYPE = "kotrail-unloadable"
        private const val ELEMENTS_SUFFIX = "KotrailUnloadableElements"
    }
}

package com.kitakkun.kotrail.gradle

import org.gradle.api.Project
import org.gradle.api.Transformer
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.AttributeDisambiguationRule
import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.attributes.MultipleCandidatesDetails
import org.gradle.api.attributes.Usage
import org.gradle.api.component.SoftwareComponent
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFile
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.plugin.FilesOptionKind
import org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.function.BiFunction

/**
 * The facts file of every JVM and Android compilation: the file the compiler plugin writes the
 * compilation's inferred facts into (`factsOut`), and the facts files of its dependencies it
 * reads (`facts`, with `factsHash`). The facts never travel in a JAR or AAR:
 *
 * - **Producer.** Every JVM or Android compilation writes
 *   `build/kotrail/facts/<target>-<compilation>/<project>.facts`, whose directory is an output of
 *   its compile task: the header alone when it has no facts, and nothing when the compile task
 *   has no sources (`NO-SOURCE`), which a published library without Kotlin sources avoids with
 *   `inferredMetadata = false`.
 *   The file of a main compilation (`main`, or an Android variant) is the artifact of a
 *   consumable configuration (`kotrailFactsElements`, `jvmKotrailFactsElements`,
 *   `releaseKotrailFactsElements`) whose attributes no Java or Kotlin consumer asks for, so only
 *   this plugin selects it, and only for a project dependency in the same build: no software
 *   component publishes the configuration. With `maven-publish`, the file is published next to
 *   the JAR or AAR as `<artifact>-<version>-kotrail-facts.facts`, a plain artifact of the Maven
 *   publication that the Gradle module metadata does not describe and the POM does not list: a
 *   Kotlin/JVM project attaches it to each of its Maven publications but the plugin markers of
 *   `java-gradle-plugin`; a multiplatform project to the publication of each JVM and Android
 *   target with a `main` compilation, and to each variant publication of `androidTarget()`; an
 *   Android library (`com.android.library`) attaches the file of one variant to each of its
 *   Maven publications, all with the same classifier.
 * - **Consumer.** A compilation reads the facts files of what its compile classpath resolved to:
 *   a project dependency's through variant reselection on the classpath (its consumable
 *   configuration), and every external module's as `group:module:version:kotrail-facts@facts`
 *   from the repositories, leniently (the classifier route). A test compilation also reads the
 *   facts files of the compilations it is associated with. The files are an input of the compile
 *   task, and the compiler gets a digest of their contents as `factsHash`, so a change to a fact
 *   alone reruns the dependent compilation, which the classpath normalization of the compile
 *   task would not.
 */
internal class FactsFiles(
    private val project: Project,
    /** Whether the compilation of this name writes its facts (the `inferredMetadata` setting). */
    private val writesFacts: (String) -> Boolean,
) {
    /** Registers the attribute rules that let a consumer pick a dependency's facts variant. Once per project. */
    fun registerAttributeSchema() {
        project.dependencies.attributesSchema { schema ->
            schema.attribute(PLATFORM_ATTRIBUTE) {
                it.compatibilityRules.add(PlatformCompatibility::class.java)
                it.disambiguationRules.add(PreferRequestedPlatform::class.java)
            }
            schema.attribute(COMPILATION_ATTRIBUTE) {
                it.compatibilityRules.add(AnyCompilation::class.java)
                it.disambiguationRules.add(PreferRequestedCompilation::class.java)
            }
        }
    }

    /**
     * Wires the facts files of [compilation] and returns the options that tell the compiler about
     * them, or null for a compilation that is not JVM or Android.
     */
    fun wire(compilation: KotlinCompilation<*>): Provider<List<SubpluginOption>>? {
        if (!compilation.isJvmLike()) return null
        val factsFile = factsFileFor(compilation)
        // The directory, not the file: the Kotlin plugin cleans and creates every output of a compile task as a
        // directory before a non-incremental compilation, and keeps it for an incremental one, whose writer merges.
        compilation.compileTaskProvider.configure { task ->
            task.outputs.dir(factsDirectoryFor(compilation)).withPropertyName("kotrailFactsDirectory")
        }
        if (compilation.isProducing()) {
            registerElements(compilation, factsFile)
            wirePublication(compilation, factsFile)
        }
        val facts = factsOfDependencies(compilation)
        compilation.compileTaskProvider.configure { task ->
            task.inputs.files(facts)
                .withPropertyName("kotrailFacts")
                .withPathSensitivity(PathSensitivity.NONE)
                .optional()
        }
        val out = project.provider<List<SubpluginOption>> {
            listOf(FilesSubpluginOption(FACTS_OUT_OPTION, listOf(factsFile.get().asFile), FilesOptionKind.INTERNAL))
        }
        // Mapped from the files, not computed now: the hash is of what the dependencies wrote, so it is read when the
        // compile task runs, after them. The transformers are classes, which the configuration cache can store.
        return out.zip(facts.elements.map(FactsOptions()), ConcatOptions())
    }

    /** `build/kotrail/facts/<target>-<compilation>/<project>.facts`. */
    fun factsFileFor(compilation: KotlinCompilation<*>): Provider<RegularFile> =
        factsDirectoryFor(compilation).map { it.file(fileName(project.name)) }

    private fun factsDirectoryFor(compilation: KotlinCompilation<*>): Provider<Directory> =
        project.layout.buildDirectory.dir("kotrail/facts/${compilation.directoryName()}")

    /**
     * The consumable configuration whose artifact is the facts file of a main compilation, for
     * the project dependencies of other modules in the build. No software component is given it,
     * so it never reaches a publication's module metadata; the file is published by classifier
     * ([wirePublication]).
     */
    private fun registerElements(compilation: KotlinCompilation<*>, factsFile: Provider<RegularFile>) {
        project.configurations.create(compilation.configurationName(ELEMENTS_SUFFIX)) { configuration ->
            configuration.description = "The Kotrail facts file of the ${compilation.name} compilation of ${project.path}, for the Kotrail plugin of dependent modules"
            configuration.isCanBeConsumed = true
            configuration.isCanBeResolved = false
            configuration.attributes.setFactsAttributes(compilation.platformType.name, compilation.name)
            configuration.outgoing.artifact(factsFile) { artifact ->
                artifact.classifier = CLASSIFIER
                artifact.extension = EXTENSION
                artifact.type = EXTENSION
                artifact.builtBy(compilation.compileTaskProvider)
            }
        }
    }

    /**
     * Publishes the facts file of a main compilation beside the JAR or AAR, as a plain artifact
     * of the Maven publication with the classifier `kotrail-facts`: never as a variant of a
     * software component, so that neither the Gradle module metadata nor the POM names anything
     * of Kotrail. Nothing without `maven-publish`, or when the compilation writes no facts. The
     * Kotlin plugin applies this plugin to a compilation after the build script has run, so the
     * setting is known here.
     */
    private fun wirePublication(compilation: KotlinCompilation<*>, factsFile: Provider<RegularFile>) {
        project.pluginManager.withPlugin("maven-publish") {
            run {
                if (!writesFacts(compilation.name)) return@run
                val multiplatform = project.pluginManager.hasPlugin(MULTIPLATFORM_PLUGIN)
                val android = compilation.platformType == KotlinPlatformType.androidJvm
                val main = compilation.name == KotlinCompilation.MAIN_COMPILATION_NAME
                when {
                    // The `main` compilation of a JVM target, or of the Android target of
                    // `com.android.kotlin.multiplatform.library`. The Kotlin plugin creates every target publication and runs
                    // these actions on it; the Android target is an external one (DecoratedExternalKotlinTarget), which
                    // delegates `mavenPublication` to ExternalKotlinTargetImpl, whose `onPublicationCreated` runs them on the
                    // single publication of the target, as for the built-in targets.
                    multiplatform && main ->
                        compilation.target.mavenPublication { publication -> publication.attachFactsFile(factsFile, compilation.compileTaskProvider) }
                    // A variant of `androidTarget()`. The Kotlin plugin creates one publication per published variant
                    // (`androidRelease`, `androidFreeDebug`), or per flavor when they are grouped by flavor (`androidFree`),
                    // and runs the target's actions on each; the publication of this variant gets its file.
                    multiplatform && android -> {
                        val target = compilation.target
                        val variants = producingVariants(target)
                        target.mavenPublication { publication ->
                            if (AndroidFactsPublications.targetVariant(publication.name, target.name, variants) == compilation.name) {
                                publication.attachFactsFile(factsFile, compilation.compileTaskProvider)
                            }
                        }
                    }
                    project.pluginManager.hasPlugin(JVM_PLUGIN) && main -> wireJvmPublications(compilation, factsFile)
                    project.pluginManager.hasPlugin(ANDROID_LIBRARY_PLUGIN) && android -> wireAndroidLibraryPublications(compilation.target)
                    else -> project.logger.info("Kotrail: the facts file of ${compilation.compileKotlinTaskName} in ${project.path} is not published.")
                }
            }
        }
    }

    /**
     * A Kotlin/JVM project. Every Maven publication of the project carries the facts file of
     * `main`: the one made `from(components["java"])`, in the build script or later (by a
     * publishing plugin, in `afterEvaluate`), and any other, such as one of a shadowed JAR, which
     * carries the same classes. The exception is the plugin markers of `java-gradle-plugin`,
     * which publish a POM alone and are told apart by name: Gradle names them
     * `<plugin>PluginMarkerMaven` (MavenPluginPublishPlugin) and marks them as aliases only after
     * creating them, which is after this action has run on them.
     */
    private fun wireJvmPublications(compilation: KotlinCompilation<*>, factsFile: Provider<RegularFile>) {
        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        publishing.publications.withType(MavenPublication::class.java).configureEach { publication ->
            if (publication.name.endsWith(PLUGIN_MARKER_SUFFIX)) return@configureEach
            publication.attachFactsFile(factsFile, compilation.compileTaskProvider)
        }
    }

    /** Whether the Maven publications of this project's Android library have been given their facts file. */
    private var androidLibraryPublicationsWired = false

    /**
     * A Kotlin/Android library (`com.android.library`). AGP makes a software component of each
     * `android.publishing { singleVariant(...) }`, named after the variant, and of each
     * `multipleVariants(...)`, named as declared (LibraryTaskManager.createComponent); the build
     * script makes the publications from them, usually in `afterEvaluate`, after this runs. Every
     * Maven publication of the project therefore gets one facts file: of the variant its
     * component is named after, else of the variant it is named after, else of `release`. The
     * choice is made when the artifact is read, after `from(component)` has run. A publication
     * that is named after no variant gets no file when there is no `release` variant writing facts.
     */
    private fun wireAndroidLibraryPublications(target: KotlinTarget) {
        if (androidLibraryPublicationsWired) return
        androidLibraryPublicationsWired = true
        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        publishing.publications.withType(MavenPublication::class.java).configureEach { publication ->
            // Every variant compilation exists by now: the Kotlin plugin creates them all before it applies compiler plugins.
            val variants = producingVariants(target).filterTo(LinkedHashSet()) { writesFacts(it) }
            if (AndroidFactsPublications.libraryVariant(null, publication.name, variants) == null) {
                project.logger.info("Kotrail: the publication ${publication.name} of ${project.path} is named after no Android variant and there is no release variant; it carries no facts file.")
                return@configureEach
            }
            // Never empty: the publication's own name or `release` is among the variants.
            val variant = project.provider {
                AndroidFactsPublications.libraryVariant(componentNameOf(publication), publication.name, variants)
            }
            publication.attachFactsFile(
                variant.flatMap { factsFileFor(target.compilations.getByName(it)) },
                Callable { listOf(target.compilations.getByName(variant.get()).compileTaskProvider) },
            )
        }
    }

    /** The Android variants of [target] whose facts other modules read: not its unit or instrumented tests. */
    private fun producingVariants(target: KotlinTarget): Set<String> =
        target.compilations.filter { it.isProducing() }.mapTo(LinkedHashSet()) { it.name }

    /** [builtBy] is anything a task dependency accepts: a task provider, or a callable returning one. */
    private fun MavenPublication.attachFactsFile(factsFile: Provider<RegularFile>, builtBy: Any) {
        artifact(factsFile) { artifact ->
            artifact.classifier = CLASSIFIER
            artifact.extension = EXTENSION
            artifact.builtBy(builtBy)
        }
    }

    /**
     * The facts files of what [compilation] compiles against: its project dependencies' facts
     * variants through variant reselection, the classifier artifacts of its external modules, and
     * the facts files of its associated compilations.
     */
    private fun factsOfDependencies(compilation: KotlinCompilation<*>): FileCollection {
        val associated = project.files(
            compilation.allAssociatedCompilations.filter { it.isJvmLike() }.map { factsFileFor(it) },
        )
        val classpath = project.configurations.findByName(compilation.compileDependencyConfigurationName)
            ?: return associated
        val platform = compilation.platformType.name
        // Projects only, of this build or an included one: a published module describes no facts variant in its
        // module metadata, so an external module is always read by classifier, below.
        val viaVariants = classpath.incoming.artifactView { view ->
            view.withVariantReselection()
            view.lenient(true)
            view.componentFilter { it is ProjectComponentIdentifier }
            view.attributes { it.setFactsAttributes(platform, compilation.name) }
        }
        val classifierRoute = project.configurations.create(compilation.configurationName(FACTS_SUFFIX)) { configuration ->
            configuration.description = "The Kotrail facts files the external dependencies of the ${compilation.name} compilation publish with the $CLASSIFIER classifier"
            configuration.isCanBeConsumed = false
            configuration.isCanBeResolved = true
            configuration.isTransitive = false
            // The classpath's own attributes, so that a module with Gradle module metadata selects the variant it
            // selected there (a multiplatform module redirects to its JVM or Android target); the dependency then
            // names the facts artifact itself. The Kotlin and Android plugins have set them by the time a compilation
            // gets here, and they are locked once resolution starts.
            configuration.attributes.copyFrom(classpath.attributes)
            configuration.dependencies.addAllLater(
                project.provider {
                    classpath.incoming.resolutionResult.allComponents
                        .mapNotNull { it.id as? ModuleComponentIdentifier }
                        .map<ModuleComponentIdentifier, Dependency> { project.dependencies.create("${it.group}:${it.module}:${it.version}:$CLASSIFIER@$EXTENSION") }
                },
            )
        }
        val viaClassifier = classifierRoute.incoming.artifactView { it.lenient(true) }.files
        return project.files(viaVariants.files, viaClassifier, associated)
    }

    /** The options for the facts files that exist among [locations], and their digest. */
    class FactsOptions : Transformer<List<SubpluginOption>, Set<FileSystemLocation>> {
        override fun transform(locations: Set<FileSystemLocation>): List<SubpluginOption> {
            val files = locations.map { it.asFile }.filter { it.isFile }.distinctBy { it.absolutePath }.sortedBy { it.absolutePath }
            // `--info` shows which facts files a compilation read, for a consumer to check its wiring.
            Logging.getLogger(FactsFiles::class.java).info("Kotrail: facts files read: ${files.joinToString { it.path }.ifEmpty { "none" }}")
            return files.map { FilesSubpluginOption(FACTS_OPTION, listOf(it), FilesOptionKind.INTERNAL) } +
                SubpluginOption(FACTS_HASH_OPTION, FactsHash.of(files))
        }
    }

    class ConcatOptions : BiFunction<List<SubpluginOption>, List<SubpluginOption>, List<SubpluginOption>> {
        override fun apply(first: List<SubpluginOption>, second: List<SubpluginOption>): List<SubpluginOption> = first + second
    }

    /** A consumer on Android also takes the facts of a JVM library, as it takes its classes. */
    abstract class PlatformCompatibility : AttributeCompatibilityRule<String> {
        override fun execute(details: CompatibilityCheckDetails<String>) {
            if (details.consumerValue == KotlinPlatformType.androidJvm.name && details.producerValue == KotlinPlatformType.jvm.name) {
                details.compatible()
            }
        }
    }

    abstract class PreferRequestedPlatform : AttributeDisambiguationRule<String> {
        override fun execute(details: MultipleCandidatesDetails<String>) {
            val requested = details.consumerValue
            if (requested != null && requested in details.candidateValues) details.closestMatch(requested)
        }
    }

    /** A test compilation reads the facts of its dependencies' main compilations, an Android variant those of a JVM `main`. */
    abstract class AnyCompilation : AttributeCompatibilityRule<String> {
        override fun execute(details: CompatibilityCheckDetails<String>) {
            details.compatible()
        }
    }

    /** The compilation of the same name (an Android variant), then `main`, then `release`. */
    abstract class PreferRequestedCompilation : AttributeDisambiguationRule<String> {
        override fun execute(details: MultipleCandidatesDetails<String>) {
            val candidates = details.candidateValues
            val choice = listOfNotNull(details.consumerValue, KotlinCompilation.MAIN_COMPILATION_NAME, "release").firstOrNull { it in candidates }
            if (choice != null) details.closestMatch(choice)
        }
    }

    private fun AttributeContainer.setFactsAttributes(platform: String, compilationName: String) {
        attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, USAGE))
        attribute(PLATFORM_ATTRIBUTE, platform)
        attribute(COMPILATION_ATTRIBUTE, compilationName)
    }

    companion object {
        const val CLASSIFIER = "kotrail-facts"
        const val EXTENSION = "facts"

        /** The usage of the facts variant, which no Java or Kotlin consumer requests. */
        const val USAGE = "kotrail-facts"

        /** The platform of the compilation that wrote the facts: `jvm` or `androidJvm`. */
        val PLATFORM_ATTRIBUTE: Attribute<String> = Attribute.of("com.kitakkun.kotrail.facts", String::class.java)

        /** The name of the compilation that wrote the facts: `main`, or an Android variant. */
        val COMPILATION_ATTRIBUTE: Attribute<String> = Attribute.of("com.kitakkun.kotrail.facts.compilation", String::class.java)

        const val FACTS_OUT_OPTION = "factsOut"
        const val FACTS_OPTION = "facts"
        const val FACTS_HASH_OPTION = "factsHash"

        private const val ELEMENTS_SUFFIX = "KotrailFactsElements"
        private const val FACTS_SUFFIX = "KotrailFacts"
        private const val JVM_PLUGIN = "org.jetbrains.kotlin.jvm"
        private const val MULTIPLATFORM_PLUGIN = "org.jetbrains.kotlin.multiplatform"
        private const val ANDROID_LIBRARY_PLUGIN = "com.android.library"

        /** The end of the name `java-gradle-plugin` gives the marker publication of each plugin it declares. */
        private const val PLUGIN_MARKER_SUFFIX = "PluginMarkerMaven"

        /**
         * The name of the component [publication] is made from, or null before `from(...)` has
         * run. Gradle keeps it behind the internal `getComponent()` of its publication (a
         * provider in current versions, the component itself in older ones), so it is read by
         * reflection and taken as unknown when that fails.
         */
        private fun componentNameOf(publication: MavenPublication): String? = runCatching {
            val component = publication.javaClass.getMethod("getComponent").invoke(publication)
            ((if (component is Provider<*>) component.orNull else component) as? SoftwareComponent)?.name
        }.getOrNull()

        /** `<module>.facts`, with the name reduced to characters safe in a file name, as the compiler plugin names it. */
        fun fileName(module: String): String = "${module.replace(Regex("[^A-Za-z0-9._-]"), "_")}.$EXTENSION"

        private fun KotlinCompilation<*>.isJvmLike(): Boolean =
            platformType == KotlinPlatformType.jvm || platformType == KotlinPlatformType.androidJvm

        /** A compilation whose facts other modules read: `main`, or an Android variant (not its unit or instrumented tests). */
        private fun KotlinCompilation<*>.isProducing(): Boolean =
            name == KotlinCompilation.MAIN_COMPILATION_NAME ||
                (platformType == KotlinPlatformType.androidJvm && !name.endsWith("Test"))

        /** `jvm-main`, `iosArm64-test`; the target of a plain Kotlin/JVM project has no name and is called `jvm`. */
        fun KotlinCompilation<*>.directoryName(): String = "${target.name.ifEmpty { "jvm" }}-$name"

        /**
         * The target, then the compilation unless it is `main`, then [suffix], in lower camel case:
         * `kotrailFacts` for Kotlin/JVM `main`, `testKotrailFacts`, `jvmKotrailFactsElements`,
         * `jvmTestKotrailFacts`, `releaseKotrailFactsElements`.
         */
        private fun KotlinCompilation<*>.configurationName(suffix: String): String {
            val parts = listOf(target.name, name.takeUnless { it == KotlinCompilation.MAIN_COMPILATION_NAME }.orEmpty(), suffix).filter { it.isNotEmpty() }
            return parts.mapIndexed { index, part ->
                if (index == 0) part.replaceFirstChar { it.lowercaseChar() } else part.replaceFirstChar { it.uppercaseChar() }
            }.joinToString("")
        }

        @Suppress("UNCHECKED_CAST")
        private fun AttributeContainer.copyFrom(source: AttributeContainer) {
            for (key in source.keySet()) {
                val value = source.getAttribute(key) ?: continue
                attribute(key as Attribute<Any>, value)
            }
        }
    }
}

/** Which Android variant's facts file a Maven publication carries, decided from names alone. */
internal object AndroidFactsPublications {
    const val RELEASE = "release"

    /**
     * A publication of a `com.android.library` project: the variant its component is named
     * after (AGP names the component of `singleVariant` after the variant), else the variant the
     * publication is named after, else `release` (a `multipleVariants` component, or a
     * publication named otherwise). Null when none of them is among [variants].
     */
    fun libraryVariant(componentName: String?, publicationName: String, variants: Set<String>): String? =
        listOfNotNull(componentName, publicationName, RELEASE).firstOrNull { it in variants }

    /**
     * A publication of `androidTarget()` in a multiplatform project, which the Kotlin plugin
     * names after the target and the variant (`androidRelease`, `androidFreeDebug`), or after
     * the target and the flavors when the variants are grouped by flavor (`androidFree`, whose
     * file is that of `freeRelease`); else `release`. Null when none of them is among [variants].
     */
    fun targetVariant(publicationName: String, targetName: String, variants: Set<String>): String? =
        variants.firstOrNull { lowerCamelCase(targetName, it) == publicationName }
            ?: variants.firstOrNull { lowerCamelCase(targetName, it) == publicationName + "Release" }
            ?: RELEASE.takeIf { it in variants }

    private fun lowerCamelCase(first: String, second: String): String =
        if (first.isEmpty()) second else first + second.replaceFirstChar { it.uppercaseChar() }
}

/** The digest of a set of facts files that the compiler gets as `factsHash`. */
internal object FactsHash {
    /**
     * SHA-256 over the SHA-256 of each file's bytes, those sorted, so that the same contents
     * give the same digest wherever the files lie (a project's build directory, the dependency
     * cache) and in whatever order they come. A file that does not exist is left out.
     */
    fun of(files: Collection<File>): String {
        val digests = files.filter { it.isFile }.map { hex(sha256(it.readBytes())) }.sorted()
        return hex(sha256(digests.joinToString("\n").toByteArray(Charsets.UTF_8)))
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

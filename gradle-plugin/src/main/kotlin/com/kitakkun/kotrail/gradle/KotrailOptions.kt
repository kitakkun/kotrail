package com.kitakkun.kotrail.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * What can be said about the plugin for the whole project, or for one compilation.
 *
 * Rule switches, severities and rule settings are not part of this DSL: they live in the
 * `kotrail.yaml` [configFile] points at, whose keys are listed in `docs/configuration.md`. That
 * keeps one list of keys rather than two, and rule configuration stays out of the build script.
 */
abstract class KotrailOptions {
    /**
     * Whether the plugin runs at all. Default `true`. A compilation this is `false` for does not
     * get the compiler plugin on its classpath.
     */
    abstract val enabled: Property<Boolean>

    /**
     * Whether the compiler plugin writes the inferred facts of this compilation, for the checks of
     * the modules that compile against it: the facts file
     * `build/kotrail/facts/<target>-<compilation>/<project>.facts` on the JVM and Android (published
     * with the `kotrail-facts` classifier), annotations (`@InferredStartsAsyncWork` and the rest) in
     * a klib. Default `true`. With `false` neither is written nor published, which suits a
     * library whose consumers do not use Kotrail; callers in
     * other modules then see the module as if it had been compiled without Kotrail. Analysis
     * within the module, and reading the facts of its dependencies, are unchanged.
     */
    abstract val inferredMetadata: Property<Boolean>

    /**
     * Whether a JVM or Android compilation also writes its inferred facts, and a digest of what
     * the model rules read of each class, into the Kotlin metadata of its class files, where
     * Kotlin's incremental compilation compares them: a fact that changes in one file then compiles
     * the files of the module that depend on it again, so that their cross-file findings appear and
     * go as a full build would report them. Default `true`. A build whose task graph publishes to a
     * repository (a task that matches [KotrailExtension.publishingTasks], `publish` and
     * `publish*Repository` by default, or any `PublishToMavenRepository` task) compiles without it,
     * so that the class files it publishes carry nothing of Kotrail; that build is therefore not
     * incremental for the compile tasks it reruns, and the next ordinary build compiles them once
     * more. `publishToMavenLocal` keeps it by default. `false` keeps the class files free of it
     * always, and incremental builds behave as they did without it. Off as well when
     * [inferredMetadata] is.
     */
    abstract val incrementalMetadata: Property<Boolean>

    /**
     * A `kotrail.yaml` with rule settings.
     *
     * Files are layered rather than replaced: a compilation is given the project's file first and
     * then the file of every override that matches it, so an override's file only lists what it
     * changes.
     */
    abstract val configFile: RegularFileProperty

    /**
     * Names of resolvable configurations of this project whose project dependencies this
     * compilation's artifact bundles into its class loader, besides the runtime class path: an
     * IDE plugin that copies a `hostRuntime` configuration into its own directory and loads it
     * through a class loader of its own. With `unloadableCode` on, what those projects' main
     * compilations recorded is reported here.
     */
    abstract val bundledConfigurations: ListProperty<String>
}

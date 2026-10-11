package com.kitakkun.kotrail

import org.jetbrains.kotlin.config.CompilerConfigurationKey

object KotrailConfigurationKeys {
    /**
     * Paths to YAML files with rule settings, in increasing precedence: a later file overrides
     * the entries of an earlier one key by key, and individual options override them all.
     */
    val CONFIG_FILE = CompilerConfigurationKey<List<String>>("configFile")

    /**
     * Every other plugin option, as `name=value`, in the order given. Names are dotted paths into
     * the configuration tree ([KotrailConfig.OPTION_NAMES]); values are read as the file reads them.
     */
    val OPTIONS = CompilerConfigurationKey<List<String>>("options")

    /** Directory of the per-file fix records of this compilation; unset means none are recorded there. */
    val FIXES_DIR = CompilerConfigurationKey<String>("fixesDir")

    /**
     * A second directory that gets the same fix records as [FIXES_DIR], for a build tool to declare
     * as an output of the compile task; unset means there is none.
     */
    val FIXES_TASK_OUTPUT_DIR = CompilerConfigurationKey<String>("fixesTaskOutputDir")

    /** Directory where this compilation records the UI composables it declares; unset means none are recorded. */
    val COMPOSABLES_DIR = CompilerConfigurationKey<String>("composablesDir")

    /** Directories of the composable records of the compilations this one is associated with, for preview coverage. */
    val ASSOCIATED_COMPOSABLES_DIRS = CompilerConfigurationKey<List<String>>("associatedComposablesDir")

    /** Directory where this compilation records every composable's complexity, for the kotrailComplexity report. */
    val COMPLEXITY_DIR = CompilerConfigurationKey<String>("complexityDir")

    /** Directory where this compilation records the outbound references the unloadable-code rule looks for. */
    val UNLOADABLE_DIR = CompilerConfigurationKey<String>("unloadableDir")

    /** The build's root directory, for paths in messages that name a file of another module. */
    val ROOT_DIR = CompilerConfigurationKey<String>("rootDir")

    /**
     * Whether the inferred facts are written for other modules to read (the facts file on the
     * JVM, annotations in a klib); unset means they are. Reading the facts of dependencies does
     * not depend on it.
     */
    val INFERRED_METADATA = CompilerConfigurationKey<Boolean>("inferredMetadata")

    /**
     * Whether a JVM compilation also writes the inferred facts (and the model surface digests)
     * into the Kotlin metadata of its class files, as plugin data that Kotlin's incremental
     * compilation compares; unset means it does. Off when [INFERRED_METADATA] is.
     */
    val IC_METADATA = CompilerConfigurationKey<Boolean>("icMetadata")

    /** Where a JVM compilation writes its facts file; unset means it writes none. */
    val FACTS_OUT = CompilerConfigurationKey<String>("factsOut")

    /** The facts files of this compilation's dependencies, in the order given; the first to describe a declaration wins. */
    val FACTS = CompilerConfigurationKey<List<String>>("facts")

    /**
     * The facts file this compilation wrote last time (its own `factsOut`), read for the declarations an incremental
     * compilation loads from the output directory instead of compiling them; unset means none is read.
     */
    val OWN_FACTS = CompilerConfigurationKey<String>("ownFacts")

    /** Main-compilation record directories of the modules on this compilation's runtime class path. */
    val BUNDLED_UNLOADABLE_DIRS = CompilerConfigurationKey<List<String>>("bundledUnloadableDir")
}

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

    /** Directory of the per-file fix records of this compilation; unset means no fixes are recorded. */
    val FIXES_DIR = CompilerConfigurationKey<String>("fixesDir")

    /** Directory where this compilation records the UI composables it declares; unset means none are recorded. */
    val COMPOSABLES_DIR = CompilerConfigurationKey<String>("composablesDir")

    /** Directories of the composable records of the compilations this one is associated with, for preview coverage. */
    val ASSOCIATED_COMPOSABLES_DIRS = CompilerConfigurationKey<List<String>>("associatedComposablesDir")

    /** Directory where this compilation records the outbound references the unloadable-code rule looks for. */
    val UNLOADABLE_DIR = CompilerConfigurationKey<String>("unloadableDir")

    /** Root record directories (`build/kotrail/unloadable`) of the modules on this compilation's runtime class path. */
    val BUNDLED_UNLOADABLE_DIRS = CompilerConfigurationKey<List<String>>("bundledUnloadableDir")
}

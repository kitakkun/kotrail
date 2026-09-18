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
}

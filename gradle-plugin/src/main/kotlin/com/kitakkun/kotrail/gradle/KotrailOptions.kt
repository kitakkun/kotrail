package com.kitakkun.kotrail.gradle

import org.gradle.api.file.RegularFileProperty
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
     * A `kotrail.yaml` with rule settings.
     *
     * Files are layered rather than replaced: a compilation is given the project's file first and
     * then the file of every override that matches it, so an override's file only lists what it
     * changes.
     */
    abstract val configFile: RegularFileProperty
}

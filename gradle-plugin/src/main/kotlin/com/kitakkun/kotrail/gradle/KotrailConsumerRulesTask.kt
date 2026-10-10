package com.kitakkun.kotrail.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Writes the shrinker rules a consumer of this module needs, as `META-INF/proguard/kotrail.pro`
 * under [outputDirectory]. The directory is a resource directory of a JVM library's main
 * compilation, so the file ships in the JAR, where R8 reads it; an Android library points its
 * `consumerProguardFiles` at the same file. With [writeRules] off the directory stays empty.
 */
@CacheableTask
abstract class KotrailConsumerRulesTask : DefaultTask() {
    @get:Input
    abstract val writeRules: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val directory = outputDirectory.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        if (!writeRules.get()) return
        val file = directory.resolve(ConsumerRules.RESOURCE_PATH)
        file.parentFile.mkdirs()
        file.writeText(ConsumerRules.TEXT)
    }
}

/** The shrinker rules shipped to the consumers of a module built with Kotrail. */
internal object ConsumerRules {
    /** Where R8 looks for the rules of a dependency JAR. */
    const val RESOURCE_PATH: String = "META-INF/proguard/kotrail.pro"

    const val TEXT: String =
        "# JVM libraries built by earlier Kotrail versions carry inferred-fact annotations (BINARY\n" +
            "# retention) whose classes consumers without Kotrail do not have. Nothing keeps them alive.\n" +
            "-dontwarn com.kitakkun.kotrail.**\n"
}

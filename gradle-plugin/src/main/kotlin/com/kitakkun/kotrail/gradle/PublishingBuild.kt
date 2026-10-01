package com.kitakkun.kotrail.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import java.util.concurrent.atomic.AtomicReference

/**
 * Whether the build publishes to a repository, so that the compile tasks leave the plugin metadata
 * Kotrail writes for incremental compilation (`icMetadata`) out of the class files it publishes.
 *
 * The answer comes from the task graph, once it is ready: a task of type [PublishToMavenRepository]
 * (every `publish...To...Repository` task of `maven-publish`), or a task whose simple name matches
 * one of the `publishingTasks` globs, [DEFAULT_TASK_NAMES] unless the build sets its own (the
 * `publish` lifecycle task and every `publish...Repository` task, Ivy publications included).
 * `publishToMavenLocal` and the `publish...ToMavenLocal` tasks do not count by default: publishing
 * to the local Maven repository is a trial of the build, and the metadata names no class, so a
 * consumer of the JAR is not affected by it, while switching it off would cost the trial a full
 * compilation and the next build another one. Until the graph is ready the answer is guessed from
 * the task names on the command line, matched against the same globs.
 *
 * The answer is read through a provider when the compile task's plugin options are read, which is
 * when it runs, or when the configuration cache stores it, after the graph is ready either way;
 * the configuration cache keys its entries on the requested tasks, so a stored answer is reused
 * only for the same request.
 */
internal object PublishingBuild {
    /** The convention of `publishingTasks`. */
    val DEFAULT_TASK_NAMES: List<String> = listOf("publish", "publish*Repository")

    /** The answer for the build [project] belongs to, with [taskNames] the `publishingTasks` globs of its extension. */
    fun of(project: Project, taskNames: Provider<List<String>>): Provider<Boolean> {
        val requested = project.gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
        val fromGraph = AtomicReference<Boolean?>(null)
        project.gradle.taskGraph.whenReady { graph ->
            val globs = taskNames.get()
            fromGraph.set(graph.allTasks.any { it is PublishToMavenRepository || isPublishingName(it.name, globs) })
        }
        return project.provider {
            fromGraph.get() ?: taskNames.get().let { globs -> requested.any { isPublishingName(it, globs) } }
        }
    }

    /** Whether the simple task name [name] matches one of [globs]: `*` matches any run of characters, `?` one character. */
    fun isPublishingName(name: String, globs: List<String>): Boolean = globs.any { globRegex(it).matches(name) }

    private fun globRegex(glob: String): Regex = buildString {
        for (c in glob) {
            when (c) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(c.toString()))
            }
        }
    }.toRegex()
}

package com.kitakkun.kotrail.gradle

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether the build publishes, so that the compile tasks leave the plugin metadata Kotrail writes
 * for incremental compilation (`icMetadata`) out of the class files it publishes.
 *
 * The answer comes from the task graph, once it is ready: a Maven publication task
 * ([AbstractPublishToMaven], which `publishToMavenLocal` and every `publish...To...Repository` task
 * are), or any task whose name starts with `publish` (the `publish` lifecycle task, Ivy
 * publications, other publishing plugins). Until the graph is ready it is guessed from the task
 * names on the command line. The answer is read through a provider when the compile task's
 * plugin options are read, which is when it runs, or when the configuration cache stores it,
 * after the graph is ready either way; the configuration cache keys its entries on the requested
 * tasks, so a stored answer is reused only for the same request.
 */
internal object PublishingBuild {
    /** The answer for the build [project] belongs to. */
    fun of(project: Project): Provider<Boolean> {
        val publishes = AtomicBoolean(project.gradle.startParameter.taskNames.any { isPublishingName(it.substringAfterLast(':')) })
        project.gradle.taskGraph.whenReady { graph -> publishes.set(graph.allTasks.any(::isPublishing)) }
        return project.provider { publishes.get() }
    }

    fun isPublishingName(name: String): Boolean = name.startsWith("publish")

    private fun isPublishing(task: Task): Boolean = task is AbstractPublishToMaven || isPublishingName(task.name)
}

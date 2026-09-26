package com.kitakkun.kotrail.gradle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * The `kotrail { }` block.
 *
 * Settings written directly in the block apply to every compilation. [test] and [compilation]
 * add overrides on top of them, which is how a project relaxes rules where it should:
 *
 * ```kotlin
 * kotrail {
 *     setting("compose.nesting.maxDepth", 4)
 *     warning("commentLength")
 *     test {
 *         disable("preferExplicitBackingField")
 *     }
 * }
 * ```
 */
abstract class KotrailExtension @Inject constructor(private val objects: ObjectFactory) : KotrailOptions() {
    /**
     * Whether `com.kitakkun.kotrail:kotrail-annotations` is added to the project's
     * `compileOnly` dependencies, so that `@HandlesWindowInsets` and `@MustBeSerializable`
     * can be written in source without the artifact reaching the project's published
     * dependencies. Default `true`. Kotlin/JVM and Kotlin/Android only: a multiplatform project
     * adds the artifact to the source sets that write these annotations itself. The metadata the
     * compiler plugin writes for other modules does not need the artifact.
     */
    abstract val annotations: Property<Boolean>

    internal val overrides: MutableList<CompilationOverride> = mutableListOf()

    /**
     * Overrides for test compilations: every compilation whose name contains `test`, which covers
     * `test`, `jvmTest`, `androidTest` and `testDebugUnitTest`.
     */
    fun test(action: Action<in KotrailOptions>) {
        addOverride({ name -> name.contains("test", ignoreCase = true) }, action)
    }

    /** Overrides for the compilation with exactly this name, such as `androidTest`. */
    fun compilation(name: String, action: Action<in KotrailOptions>) {
        addOverride({ it == name }, action)
    }

    private fun addOverride(matches: (String) -> Boolean, action: Action<in KotrailOptions>) {
        val options = objects.newInstance(KotrailOptions::class.java)
        action.execute(options)
        overrides += CompilationOverride(matches, options)
    }

    internal class CompilationOverride(
        val matches: (String) -> Boolean,
        val options: KotrailOptions,
    )
}

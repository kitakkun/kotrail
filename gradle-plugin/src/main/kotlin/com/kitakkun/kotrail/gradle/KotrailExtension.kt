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

    /**
     * Whether the module ships shrinker rules for its consumers: `-dontwarn com.kitakkun.kotrail.**`,
     * so that R8 or ProGuard in an app without Kotrail does not fail on inferred-fact annotation
     * classes it cannot find. Default `true`. The JVM and Android outputs of this version need no
     * rule (the facts are in a facts file published as an artifact of its own, and the class files reference nothing of Kotrail); it
     * stays for consumers that also shrink JVM libraries built by earlier versions, whose class
     * files carry the annotations. An Android library (`com.android.library`) ships the rules
     * through `consumerProguardFiles`; a Kotlin/JVM module and the JVM targets of a multiplatform
     * one ship them in the JAR as `META-INF/proguard/kotrail.pro`.
     */
    abstract val consumerRules: Property<Boolean>

    /**
     * Whether an Android application (`com.android.application`) leaves `META-INF/kotrail/` out of
     * its APK and bundle, with an entry in `android.packaging.resources.excludes`. Libraries built
     * by earlier versions of Kotrail carry their facts file there, as
     * `META-INF/kotrail/<module>.facts`; this version publishes it as a separate `kotrail-facts`
     * artifact instead, so the exclude only matters for those older libraries. Default `true`.
     */
    abstract val packagingExcludes: Property<Boolean>

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

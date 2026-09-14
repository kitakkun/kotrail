package com.kitakkun.kotrail.fir.compose.locals

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

object CompositionLocalNames {
    val ANNOTATIONS_PACKAGE = FqName("com.kitakkun.kotrail.compose.locals")
    val COMPOSITION_LOCAL_ROOT = ClassId(ANNOTATIONS_PACKAGE, Name.identifier("CompositionLocalRoot"))
    val REQUIRED_COMPOSITION_LOCAL = ClassId(ANNOTATIONS_PACKAGE, Name.identifier("RequiredCompositionLocal"))
    val INFERRED_COMPOSITION_LOCALS = ClassId(ANNOTATIONS_PACKAGE, Name.identifier("InferredCompositionLocals"))
    val INFERRED_REQUIRED_COMPOSITION_LOCAL = ClassId(ANNOTATIONS_PACKAGE, Name.identifier("InferredRequiredCompositionLocal"))

    val READS_PARAM = Name.identifier("reads")
    val PROVIDES_PARAM = Name.identifier("provides")

    val RUNTIME = FqName("androidx.compose.runtime")
    val COMPOSITION_LOCAL = ClassId(RUNTIME, Name.identifier("CompositionLocal"))
    val PROVIDABLE_COMPOSITION_LOCAL = ClassId(RUNTIME, Name.identifier("ProvidableCompositionLocal"))
    val COMPOSITION_LOCAL_PROVIDER = CallableId(RUNTIME, Name.identifier("CompositionLocalProvider"))

    /** The factories whose default lambda decides whether a local is required. */
    val FACTORIES: Set<CallableId> = setOf(
        CallableId(RUNTIME, Name.identifier("compositionLocalOf")),
        CallableId(RUNTIME, Name.identifier("staticCompositionLocalOf")),
        CallableId(RUNTIME, Name.identifier("compositionLocalWithComputedDefaultOf")),
    )

    val CURRENT = Name.identifier("current")
    val PROVIDES = Name.identifier("provides")
    val PROVIDES_DEFAULT = Name.identifier("providesDefault")
}

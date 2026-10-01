package com.kitakkun.kotrail.fir.compose

import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/** Classpath types whose type arguments are their contents: collections, arrays, `Pair`, `Triple`, `Sequence`. */
private val CONTAINER_PACKAGES = setOf(FqName("kotlin.collections"), FqName("kotlinx.collections.immutable"))
private val CONTAINER_CLASSES = setOf("kotlin.Array", "kotlin.Pair", "kotlin.Triple", "kotlin.sequences.Sequence")
    .map { ClassId.topLevel(FqName(it)) }
    .toSet()

/**
 * Whether this is a classpath type whose type arguments are what it holds, so that a walk over
 * what a value contains goes on into them. Checked on the type as written as well as expanded:
 * `ArrayList` is a type alias of a Java class on the JVM, and its declared name says it is a collection.
 */
fun ConeKotlinType.holdsItsArguments(): Boolean {
    val classId = (this as? ConeClassLikeType)?.lookupTag?.classId ?: return false
    return classId.packageFqName in CONTAINER_PACKAGES || classId in CONTAINER_CLASSES
}

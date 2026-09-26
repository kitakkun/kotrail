package com.kitakkun.kotrail.fir.inferred

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Reading the metadata the plugin writes: an annotation whose parameters are arrays of strings,
 * found on a declaration by class id and read parameter by parameter. The same helpers read a
 * hand-written annotation of that shape.
 */
object InferredMetadata {
    /** The annotation of class [classId] on this symbol, with its arguments resolved, or null. */
    fun FirBasedSymbol<*>.annotationOf(session: FirSession, classId: ClassId): FirAnnotation? =
        resolvedAnnotationsWithArguments.firstOrNull { it.toAnnotationClassId(session) == classId }

    /** The string elements of the array argument named [parameter]; empty when absent. */
    fun FirAnnotation.strings(parameter: Name): List<String> =
        arrayElements(findArgumentByName(parameter, returnFirstWhenNotFound = false))
            .mapNotNull { (it as? FirLiteralExpression)?.value as? String }

    /** The elements of an array argument, however the compiler spelled it: a collection literal, a vararg, or `arrayOf(...)`. */
    fun arrayElements(expression: FirExpression?): List<FirExpression> =
        when (val expr = expression?.unwrapArgument()) {
            null -> emptyList()
            is FirVarargArgumentsExpression -> expr.arguments.map { it.unwrapArgument() }
            is FirCall -> expr.argumentList.arguments.map { it.unwrapArgument() }
            else -> emptyList()
        }
}

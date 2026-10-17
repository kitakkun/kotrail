package com.kitakkun.kotrail.compat

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirSamConversionExpression
import org.jetbrains.kotlin.name.ClassId

/**
 * The class a qualifier such as `Modifier` or `GlobalScope` resolves to, or `null` for a
 * package qualifier.
 *
 * Since 2.4.20: `FirResolvedQualifier.classId` is an extension property in
 * `org.jetbrains.kotlin.fir.expressions` rather than a member, so it needs an import there
 * that does not resolve on earlier versions.
 */
val FirResolvedQualifier.qualifierClassId: ClassId?
    get() = classId

/**
 * The fake-source kind for elements a plugin makes up, used for the diagnostic ranges the
 * comment-length rule cuts out of a file.
 *
 * Since 2.4.20: `KtFakeSourceElementKind.PluginGenerated` is a sealed class with `Default` and
 * `Custom(marker)` subclasses rather than an object.
 */
val PLUGIN_GENERATED_SOURCE_KIND: KtFakeSourceElementKind = KtFakeSourceElementKind.PluginGenerated

/**
 * The expression a call argument converts to the parameter's function type, such as a lambda
 * passed to a `fun interface` parameter, or the expression itself when nothing converts it.
 *
 * Since 2.4.0: a SAM conversion, like a conversion to another kind of function type, is a
 * `FirFunctionTypeConversionExpression` rather than a `FirSamConversionExpression`.
 */
fun FirExpression.unwrapFunctionConversion(): FirExpression = (this as? FirSamConversionExpression)?.expression ?: this

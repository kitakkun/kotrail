@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.kitakkun.kotrail.compat

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.defaultType

/**
 * Builds a call to [constructor] — an annotation constructor taking a single `vararg String`
 * parameter — with [values] as its argument, and attaches it to [declaration] so that the
 * annotation is written into the module's metadata.
 *
 * Since 2.4.0: `IrGeneratedDeclarationsRegistrar.addMetadataVisibleAnnotationsToElement` takes
 * `List<IrAnnotation>` where it used to take `List<IrConstructorCall>`. `IrAnnotation` is itself an
 * `IrConstructorCall` subclass on every version this shim covers, so the same `IrAnnotationImpl`
 * satisfies both signatures. The shim is kept as the seam for the pre-2.3.20 compilers that have
 * no `IrAnnotation` at all and need `IrConstructorCallImpl` here.
 */
fun addStringVarargMetadataAnnotation(
    pluginContext: IrPluginContext,
    declaration: IrDeclaration,
    constructor: IrConstructorSymbol,
    values: List<String>,
) = addStringArraysMetadataAnnotation(pluginContext, declaration, constructor, listOf(values))

/**
 * The general form of [addStringVarargMetadataAnnotation]: [constructor] takes one `Array<String>`
 * (or `vararg String`) parameter per entry of [arguments], in order, and an annotation with no
 * parameters takes an empty list. Annotation array arguments are varargs in IR either way.
 */
fun addStringArraysMetadataAnnotation(
    pluginContext: IrPluginContext,
    declaration: IrDeclaration,
    constructor: IrConstructorSymbol,
    arguments: List<List<String>>,
) {
    val stringType = pluginContext.irBuiltIns.stringType
    val parameters = constructor.owner.parameters
    require(parameters.size == arguments.size) { "${constructor.owner.name} takes ${parameters.size} parameters, got ${arguments.size} arguments" }
    val annotation = IrAnnotationImpl(
        startOffset = UNDEFINED_OFFSET,
        endOffset = UNDEFINED_OFFSET,
        type = (constructor.owner.parent as IrClass).defaultType,
        symbol = constructor,
        typeArgumentsCount = 0,
        constructorTypeArgumentsCount = 0,
    ).apply {
        for ((index, values) in arguments.withIndex()) {
            this.arguments[index] = IrVarargImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = parameters[index].type,
                varargElementType = stringType,
                elements = values.map { IrConstImpl.string(UNDEFINED_OFFSET, UNDEFINED_OFFSET, stringType, it) },
            )
        }
    }
    pluginContext.metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement(declaration, annotation)
}

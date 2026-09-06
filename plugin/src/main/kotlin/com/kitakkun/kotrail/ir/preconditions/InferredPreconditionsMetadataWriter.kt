@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.kitakkun.kotrail.ir.preconditions

import com.kitakkun.kotrail.fir.preconditions.PreconditionNames
import com.kitakkun.kotrail.fir.preconditions.preconditionService
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrMutableAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Writes `@InferredPreconditions` onto every non-private function and class whose leading
 * `require` / `check` calls could be expressed in the precondition language, so that call sites
 * in other modules can be checked. The analysis lives in the FIR session component and was
 * cached while bodies were available; the IR declaration reaches it through its metadata source.
 */
class InferredPreconditionsMetadataWriter : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        if (!pluginContext.afterK2) return
        val annotationClass = pluginContext.finderForBuiltins()
            .findClass(PreconditionNames.INFERRED_PRECONDITIONS) ?: return
        val constructor = annotationClass.owner.constructors.singleOrNull()?.symbol ?: return
        val writer = Writer(pluginContext, constructor)
        moduleFragment.files.forEach { it.acceptChildrenVoid(writer) }
    }

    private class Writer(
        private val pluginContext: IrPluginContext,
        private val constructor: IrConstructorSymbol,
    ) : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitSimpleFunction(declaration: IrSimpleFunction) {
            declaration.acceptChildrenVoid(this)
            val fir = (declaration.metadata as? FirMetadataSource.Function)?.fir ?: return
            write(declaration, fir)
        }

        override fun visitClass(declaration: IrClass) {
            declaration.acceptChildrenVoid(this)
            val fir = (declaration.metadata as? FirMetadataSource.Class)?.fir ?: return
            write(declaration, fir)
        }

        private fun <T> write(declaration: T, fir: FirDeclaration) where T : IrDeclarationWithVisibility, T : IrMutableAnnotationContainer {
            if (declaration.hasAnnotation(PreconditionNames.INFERRED_PRECONDITIONS)) return
            if (declaration.visibility == DescriptorVisibilities.PRIVATE) return
            if (declaration.visibility == DescriptorVisibilities.LOCAL) return
            val parent = declaration.parent
            if (parent !is IrFile && parent !is IrClass) return

            val service = fir.moduleData.session.preconditionService
            val conditions = service.renderedPreconditions(fir.symbol)
            if (conditions.isEmpty()) return
            pluginContext.metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement(declaration, buildAnnotation(conditions))
        }

        private fun buildAnnotation(conditions: List<String>): IrAnnotationImpl {
            val annotationType = (constructor.owner.parent as IrClass).defaultType
            val parameter = constructor.owner.parameters.single()
            val stringType = pluginContext.irBuiltIns.stringType
            return IrAnnotationImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = annotationType,
                symbol = constructor,
                typeArgumentsCount = 0,
                constructorTypeArgumentsCount = 0,
            ).apply {
                arguments[0] = IrVarargImpl(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    type = parameter.type,
                    varargElementType = stringType,
                    elements = conditions.map { IrConstImpl.string(UNDEFINED_OFFSET, UNDEFINED_OFFSET, stringType, it) },
                )
            }
        }
    }
}

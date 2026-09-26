@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.kitakkun.kotrail.ir.concurrency

import com.kitakkun.kotrail.compat.addStringArraysMetadataAnnotation
import com.kitakkun.kotrail.fir.concurrency.AsyncWorkService
import com.kitakkun.kotrail.fir.concurrency.asyncWorkService
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Writes `@InferredStartsAsyncWork` onto every non-private, non-suspending function whose body
 * starts work its caller cannot wait for, so that callers in other modules can be checked. The
 * analysis lives in the FIR session component; the IR function reaches it through the FIR
 * declaration attached as its metadata source.
 */
class InferredStartsAsyncWorkMetadataWriter : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        if (!pluginContext.afterK2) return
        val annotationClass = pluginContext.finderForBuiltins().findClass(AsyncWorkService.INFERRED_STARTS_ASYNC_WORK) ?: return
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
            if (declaration.isSuspend) return
            if (declaration.hasAnnotation(AsyncWorkService.INFERRED_STARTS_ASYNC_WORK)) return
            if (declaration.visibility == DescriptorVisibilities.PRIVATE) return
            if (declaration.visibility == DescriptorVisibilities.LOCAL) return
            val parent = declaration.parent
            if (parent !is IrFile && parent !is IrClass) return

            val fir = (declaration.metadata as? FirMetadataSource.Function)?.fir as? FirNamedFunction ?: return
            if (!fir.moduleData.session.asyncWorkService.startsAsyncWork(fir.symbol)) return

            addStringArraysMetadataAnnotation(pluginContext, declaration, constructor, emptyList())
        }
    }
}

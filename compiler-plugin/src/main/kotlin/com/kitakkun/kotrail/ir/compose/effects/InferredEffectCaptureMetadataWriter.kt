@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.kitakkun.kotrail.ir.compose.effects

import com.kitakkun.kotrail.compat.addStringVarargMetadataAnnotation
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.effects.effectCaptureService
import com.kitakkun.kotrail.ir.inferredAnnotationConstructor
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Writes `@InferredEffectCapture` onto every non-private composable whose body keeps some of its
 * parameters for the life of an effect, so that callers in other modules can be checked. The
 * analysis itself lives in the FIR session component; the IR function reaches it through the FIR
 * declaration attached as its metadata source.
 */
class InferredEffectCaptureMetadataWriter : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        if (!pluginContext.afterK2) return
        val constructor = pluginContext.inferredAnnotationConstructor(ComposeNames.INFERRED_EFFECT_CAPTURE, listOf("captured"))
        val writer = Writer(pluginContext, constructor)
        moduleFragment.files.forEach { it.acceptChildrenVoid(writer) }
    }

    private class Writer(
        private val pluginContext: IrPluginContext,
        private val constructor: IrConstructorSymbol,
    ) : IrVisitorVoid() {
        override fun visitElement(element: org.jetbrains.kotlin.ir.IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitSimpleFunction(declaration: IrSimpleFunction) {
            declaration.acceptChildrenVoid(this)
            if (!declaration.hasAnnotation(ComposeNames.COMPOSABLE)) return
            if (declaration.hasAnnotation(ComposeNames.INFERRED_EFFECT_CAPTURE)) return
            if (declaration.visibility == DescriptorVisibilities.PRIVATE) return
            if (declaration.visibility == DescriptorVisibilities.LOCAL) return
            val parent = declaration.parent
            if (parent !is IrFile && parent !is IrClass) return

            val fir = (declaration.metadata as? FirMetadataSource.Function)?.fir as? FirNamedFunction ?: return
            val captured = fir.moduleData.session.effectCaptureService.capturedParameters(fir.symbol)
            if (captured.isEmpty()) return

            addStringVarargMetadataAnnotation(pluginContext, declaration, constructor, captured.toList())
        }
    }
}

@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.kitakkun.kotrail.ir.compose.insets

import com.kitakkun.kotrail.compat.addStringVarargMetadataAnnotation
import com.kitakkun.kotrail.fir.compose.insets.WindowInsetsNames
import com.kitakkun.kotrail.fir.compose.insets.windowInsetsHandlingService
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
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Writes `@InferredWindowInsetsHandling` onto every non-private composable that has no declared
 * `@HandlesWindowInsets` contract, so that other modules can verify calls into this one.
 *
 * The analysis itself lives in the FIR session component; the IR function reaches it through the
 * FIR declaration attached as its metadata source.
 */
class InferredWindowInsetsMetadataWriter : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        if (!pluginContext.afterK2) return
        val annotationClass = pluginContext.finderForBuiltins()
            .findClass(WindowInsetsNames.INFERRED_WINDOW_INSETS_HANDLING) ?: return
        val constructor = annotationClass.owner.constructors.singleOrNull()?.symbol ?: return
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
            if (!declaration.hasAnnotation(WindowInsetsNames.COMPOSABLE)) return
            if (declaration.hasAnnotation(WindowInsetsNames.HANDLES_WINDOW_INSETS)) return
            if (declaration.hasAnnotation(WindowInsetsNames.INFERRED_WINDOW_INSETS_HANDLING)) return
            if (declaration.visibility == DescriptorVisibilities.PRIVATE) return
            if (declaration.visibility == DescriptorVisibilities.LOCAL) return
            val parent = declaration.parent
            if (parent !is IrFile && parent !is IrClass) return

            val fir = (declaration.metadata as? FirMetadataSource.Function)?.fir as? FirNamedFunction ?: return
            val service = fir.moduleData.session.windowInsetsHandlingService
            val handled = service.handledInsets(fir.symbol).handled
            if (handled.isEmpty) return

            addStringVarargMetadataAnnotation(pluginContext, declaration, constructor, handled.encode())
        }
    }
}

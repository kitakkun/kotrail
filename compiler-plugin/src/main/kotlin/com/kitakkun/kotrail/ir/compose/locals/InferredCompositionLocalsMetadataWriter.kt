@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.kitakkun.kotrail.ir.compose.locals

import com.kitakkun.kotrail.compat.addStringArraysMetadataAnnotation
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.locals.CompositionLocalNames
import com.kitakkun.kotrail.fir.compose.locals.LocalsAnalysis
import com.kitakkun.kotrail.fir.compose.locals.REQUIRED_MARK
import com.kitakkun.kotrail.fir.compose.locals.compositionLocalService
import com.kitakkun.kotrail.ir.inferredAnnotationConstructor
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Writes `@InferredCompositionLocals` onto every non-private composable that reads or provides
 * a composition local, and `@InferredRequiredCompositionLocal` onto every non-private local
 * whose default throws, so that other modules can verify their roots against this one.
 *
 * The analysis lives in the FIR session component and was cached while bodies were available;
 * the IR declaration reaches it through its metadata source.
 */
class InferredCompositionLocalsMetadataWriter : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        if (!pluginContext.afterK2) return
        val functionConstructor = pluginContext.inferredAnnotationConstructor(CompositionLocalNames.INFERRED_COMPOSITION_LOCALS, listOf("reads", "provides"))
        val propertyConstructor = pluginContext.inferredAnnotationConstructor(CompositionLocalNames.INFERRED_REQUIRED_COMPOSITION_LOCAL, emptyList())
        val writer = Writer(pluginContext, functionConstructor, propertyConstructor)
        moduleFragment.files.forEach { it.acceptChildrenVoid(writer) }
    }

    private class Writer(
        private val pluginContext: IrPluginContext,
        private val functionConstructor: IrConstructorSymbol,
        private val propertyConstructor: IrConstructorSymbol,
    ) : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitSimpleFunction(declaration: IrSimpleFunction) {
            declaration.acceptChildrenVoid(this)
            if (!declaration.hasAnnotation(ComposeNames.COMPOSABLE)) return
            if (declaration.hasAnnotation(CompositionLocalNames.INFERRED_COMPOSITION_LOCALS)) return
            if (!declaration.isExported()) return

            val fir = (declaration.metadata as? FirMetadataSource.Function)?.fir as? FirNamedFunction ?: return
            val analysis = fir.moduleData.session.compositionLocalService.analysis(fir.symbol)
            writeAnalysis(declaration, analysis)
        }

        /** Reads (a required one marked with `!`, so that a reader of a private local carries the requirement) and provides. */
        private fun writeAnalysis(declaration: org.jetbrains.kotlin.ir.declarations.IrDeclaration, analysis: LocalsAnalysis) {
            val reads = analysis.reads.keys.sorted().map { if (it in analysis.required) it + REQUIRED_MARK else it }
            val provides = analysis.provides.entries
                .flatMap { (parameter, locals) -> locals.map { "$parameter:$it" } }
                .sorted()
            if (reads.isEmpty() && provides.isEmpty()) return
            addStringArraysMetadataAnnotation(pluginContext, declaration, functionConstructor, listOf(reads, provides))
        }

        override fun visitProperty(declaration: IrProperty) {
            declaration.acceptChildrenVoid(this)
            if (!declaration.isExported()) return
            val fir = (declaration.metadata as? FirMetadataSource.Property)?.fir ?: return
            val service = fir.moduleData.session.compositionLocalService
            if (!declaration.hasAnnotation(CompositionLocalNames.INFERRED_REQUIRED_COMPOSITION_LOCAL) && service.isRequiredBySource(fir.symbol)) {
                addStringArraysMetadataAnnotation(pluginContext, declaration, propertyConstructor, emptyList())
            }
            // A composable getter is a reader: `val colors: Colors @Composable get() = LocalColors.current`.
            if (declaration.getter?.hasAnnotation(ComposeNames.COMPOSABLE) == true && !declaration.hasAnnotation(CompositionLocalNames.INFERRED_COMPOSITION_LOCALS)) {
                writeAnalysis(declaration, service.getterAnalysis(fir.symbol))
            }
        }

        private fun IrSimpleFunction.isExported(): Boolean =
            visibility != DescriptorVisibilities.PRIVATE && visibility != DescriptorVisibilities.LOCAL &&
                (parent is IrFile || parent is IrClass)

        private fun IrProperty.isExported(): Boolean =
            visibility != DescriptorVisibilities.PRIVATE && visibility != DescriptorVisibilities.LOCAL &&
                (parent is IrFile || parent is IrClass)
    }
}

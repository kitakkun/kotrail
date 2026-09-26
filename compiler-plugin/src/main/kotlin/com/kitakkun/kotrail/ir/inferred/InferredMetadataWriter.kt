@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.kitakkun.kotrail.ir.inferred

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.addStringArraysMetadataAnnotation
import com.kitakkun.kotrail.ir.inferredAnnotationConstructor
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrMutableAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.ClassId

/**
 * One inferred fact as the IR writer sees it: which annotation to write, on which declarations,
 * with which arrays. The FIR side of the same fact is a
 * [com.kitakkun.kotrail.fir.inferred.InferredFactService]; the IR declaration reaches it through
 * the FIR declaration attached as its metadata source.
 */
interface InferredFact<D : IrDeclaration, F> {
    val rule: KotrailRule
    val annotation: ClassId

    /** The annotation's parameters, each an `Array<String>`, in order; empty for a marker. */
    val parameters: List<String>

    /** Whether the fact applies to [declaration] at all (a composable, a non-suspend function). */
    fun applies(declaration: D): Boolean = true

    /** The arrays to write for [fir], one per [parameters], or null when there is nothing to write. */
    fun arrays(declaration: D, fir: F): List<List<String>>?
}

typealias InferredFunctionFact = InferredFact<IrSimpleFunction, FirNamedFunction>
typealias InferredPropertyFact = InferredFact<IrProperty, FirProperty>
typealias InferredClassFact = InferredFact<IrClass, FirRegularClass>

/**
 * Writes every inferred fact whose rule is on as metadata onto the declarations of the module,
 * for the modules that compile against it: every exported (non-private, non-local, directly in
 * a file or a class) function, property or class, unless it already carries the annotation.
 * The annotations go through `metadataDeclarationRegistrar`, so consumers read them with no
 * extra wiring, and against a stub class when the annotations artifact is absent.
 */
class InferredMetadataWriter(
    private val functionFacts: List<InferredFunctionFact>,
    private val propertyFacts: List<InferredPropertyFact>,
    private val classFacts: List<InferredClassFact>,
) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        if (!pluginContext.afterK2) return
        if (functionFacts.isEmpty() && propertyFacts.isEmpty() && classFacts.isEmpty()) return
        moduleFragment.files.forEach { it.acceptChildrenVoid(Writer(pluginContext)) }
    }

    private inner class Writer(private val pluginContext: IrPluginContext) : IrVisitorVoid() {
        private val constructors = HashMap<ClassId, IrConstructorSymbol>()

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitSimpleFunction(declaration: IrSimpleFunction) {
            declaration.acceptChildrenVoid(this)
            val fir = (declaration.metadata as? FirMetadataSource.Function)?.fir as? FirNamedFunction ?: return
            for (fact in functionFacts) write(fact, declaration, fir)
        }

        override fun visitProperty(declaration: IrProperty) {
            declaration.acceptChildrenVoid(this)
            val fir = (declaration.metadata as? FirMetadataSource.Property)?.fir ?: return
            for (fact in propertyFacts) write(fact, declaration, fir)
        }

        override fun visitClass(declaration: IrClass) {
            declaration.acceptChildrenVoid(this)
            val fir = (declaration.metadata as? FirMetadataSource.Class)?.fir as? FirRegularClass ?: return
            for (fact in classFacts) write(fact, declaration, fir)
        }

        private fun <D, F> write(fact: InferredFact<D, F>, declaration: D, fir: F) where D : IrDeclarationWithVisibility, D : IrMutableAnnotationContainer {
            if (!declaration.isExported()) return
            if (declaration.hasAnnotation(fact.annotation)) return
            if (!fact.applies(declaration)) return
            val arrays = fact.arrays(declaration, fir) ?: return
            val constructor = constructors.getOrPut(fact.annotation) { pluginContext.inferredAnnotationConstructor(fact.annotation, fact.parameters) }
            addStringArraysMetadataAnnotation(pluginContext, declaration, constructor, arrays)
        }

        private fun IrDeclarationWithVisibility.isExported(): Boolean =
            visibility != DescriptorVisibilities.PRIVATE && visibility != DescriptorVisibilities.LOCAL &&
                (parent is IrFile || parent is IrClass)
    }
}

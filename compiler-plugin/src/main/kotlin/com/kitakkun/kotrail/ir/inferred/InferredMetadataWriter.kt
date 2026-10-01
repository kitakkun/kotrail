@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.kitakkun.kotrail.ir.inferred

import com.kitakkun.kotrail.KotrailConfig
import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.KotrailVersion
import com.kitakkun.kotrail.compat.addStringArraysMetadataAnnotation
import com.kitakkun.kotrail.fir.inferred.FactKeys
import com.kitakkun.kotrail.inferred.FactsFile
import com.kitakkun.kotrail.ir.inferredAnnotationConstructor
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
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
import org.jetbrains.kotlin.platform.jvm.isJvm
import java.io.File

/**
 * One inferred fact as the IR writer sees it: its name in a facts file, the annotation it becomes
 * in a klib, on which declarations, with which arrays. The FIR side of the same fact is a
 * [com.kitakkun.kotrail.fir.inferred.InferredFactService]; the IR declaration reaches it through
 * the FIR declaration attached as its metadata source.
 */
interface InferredFact<D : IrDeclaration, F : FirDeclaration> {
    /** The rules that read the fact: it is written while any of them is enabled. */
    val rules: List<KotrailRule>

    /** Whether the fact is written: one of [rules] is on, unless the fact says otherwise (as its FIR service does). */
    fun isOn(config: KotrailConfig): Boolean = rules.any(config::isEnabled)

    /** The fact's name in a facts file, one of [com.kitakkun.kotrail.inferred.InferredFactNames]. */
    val name: String

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
 * Where a JVM compilation puts its facts file: the path of the `factsOut` plugin option (null
 * when absent, and then nothing is written); the module name for the header, read when the IR
 * runs, from the configuration the compiler finished setting up by then; and the build's root
 * directory (the `rootDir` plugin option) the source paths are written relative to.
 */
class FactsFileOutput(val file: File?, val module: () -> String?, val rootDir: String?)

/**
 * Writes every inferred fact with one of its rules on for the modules that compile against this one:
 * for every exported (non-private, non-local, directly in a file or a class) function, property
 * or class, unless it already carries the fact's annotation (a hand-written contract).
 *
 * - On the JVM (Android included) the facts go to the facts file at the `factsOut` path (see
 *   [FactsFile]), source paths relative to the build's root directory, and neither the class
 *   files nor the output directory carry anything of Kotrail. Under incremental compilation the
 *   module fragment holds only the recompiled files: their entries replace what the facts file
 *   said about them, and the entries of files that no longer exist are dropped. The file is
 *   written on every compilation, the header alone when there is no fact, and never deleted.
 *   Without `factsOut` nothing is written.
 * - For a klib (Native, JS, Wasm, the metadata compilation of shared source sets) they are
 *   annotations, added through `metadataDeclarationRegistrar` so consumers read them with no
 *   extra wiring, against a stub class when the annotations artifact is absent.
 */
class InferredMetadataWriter(
    private val functionFacts: List<InferredFunctionFact>,
    private val propertyFacts: List<InferredPropertyFact>,
    private val classFacts: List<InferredClassFact>,
    private val factsFile: FactsFileOutput,
) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        if (!pluginContext.afterK2) return
        // On the JVM the file is written on every compilation, the header alone when no fact is on or found, so that
        // a module without facts still has a file to publish.
        if (pluginContext.platform.isJvm()) {
            writeFactsFile(moduleFragment)
            return
        }
        if (functionFacts.isEmpty() && propertyFacts.isEmpty() && classFacts.isEmpty()) return
        writeAnnotations(moduleFragment, pluginContext)
    }

    private fun writeFactsFile(moduleFragment: IrModuleFragment) {
        val output = factsFile.file ?: return
        val module = factsFile.module() ?: moduleFragment.name.asString().removeSurrounding("<", ">")
        val entries = ArrayList<FactsFile.Entry>()
        for (file in moduleFragment.files) {
            val path = FactsFile.sourcePath(file.fileEntry.name, factsFile.rootDir)
            file.acceptChildrenVoid(
                object : Walker() {
                    override fun <D, F : FirDeclaration> emit(fact: InferredFact<D, F>, declaration: D, fir: F, arrays: List<List<String>>)
                        where D : IrDeclarationWithVisibility, D : IrMutableAnnotationContainer {
                        val id = FactKeys.id(fir.symbol) ?: return
                        val signature = FactKeys.signature(fir.symbol, fir.moduleData.session)
                        entries += FactsFile.Entry(path, id, signature, mapOf(fact.name to fact.parameters.zip(arrays).toMap()))
                    }
                },
            )
        }
        val compiled = moduleFragment.files.mapTo(HashSet()) { FactsFile.sourcePath(it.fileEntry.name, factsFile.rootDir) }
        FactsFile.update(output, module, KotrailVersion.VERSION, entries, compiled, factsFile.rootDir)
    }

    private fun writeAnnotations(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val constructors = HashMap<ClassId, IrConstructorSymbol>()
        val walker = object : Walker() {
            override fun <D, F : FirDeclaration> emit(fact: InferredFact<D, F>, declaration: D, fir: F, arrays: List<List<String>>)
                where D : IrDeclarationWithVisibility, D : IrMutableAnnotationContainer {
                val constructor = constructors.getOrPut(fact.annotation) { pluginContext.inferredAnnotationConstructor(fact.annotation, fact.parameters) }
                addStringArraysMetadataAnnotation(pluginContext, declaration, constructor, arrays)
            }
        }
        moduleFragment.files.forEach { it.acceptChildrenVoid(walker) }
    }

    /** Finds every exported declaration with a fact to write and hands the fact's arrays to [emit]. */
    private abstract inner class Walker : IrVisitorVoid() {
        abstract fun <D, F : FirDeclaration> emit(fact: InferredFact<D, F>, declaration: D, fir: F, arrays: List<List<String>>)
            where D : IrDeclarationWithVisibility, D : IrMutableAnnotationContainer

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

        private fun <D, F : FirDeclaration> write(fact: InferredFact<D, F>, declaration: D, fir: F) where D : IrDeclarationWithVisibility, D : IrMutableAnnotationContainer {
            if (!declaration.isExported()) return
            if (declaration.hasAnnotation(fact.annotation)) return
            if (!fact.applies(declaration)) return
            val arrays = fact.arrays(declaration, fir) ?: return
            emit(fact, declaration, fir, arrays)
        }

        private fun IrDeclarationWithVisibility.isExported(): Boolean =
            visibility != DescriptorVisibilities.PRIVATE && visibility != DescriptorVisibilities.LOCAL &&
                (parent is IrFile || parent is IrClass)
    }
}

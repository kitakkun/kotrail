@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.kitakkun.kotrail.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.DescriptorlessExternalPackageFragmentSymbol
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * The constructor of an inferred-metadata annotation (`@InferredStartsAsyncWork`,
 * `@InferredEffectCapture`, ...) for the writers that put one on a declaration: the class from
 * `kotrail-annotations` when that artifact is on the compile classpath, otherwise a stub of it
 * declared in an external package fragment, as the compiler does for its own special
 * annotations, so that nothing is emitted for the stub itself.
 *
 * The metadata therefore never needs the artifact: on the JVM the annotation is written by name
 * and a reader without the class ignores it, and a klib consumer's partial linkage removes an
 * annotation whose class it cannot find, silently by default. A consumer that runs Kotrail matches
 * the annotation by its class id, on either kind of output, and needs no class either. That keeps
 * a published library free of any Kotrail dependency while its callers in other modules are still
 * checked against what its bodies do.
 *
 * [arrayParameters] names the constructor's parameters, each an `Array<String>`, in order.
 */
fun IrPluginContext.inferredAnnotationConstructor(classId: ClassId, arrayParameters: List<String>): IrConstructorSymbol {
    finderForBuiltins().findClass(classId)?.owner?.constructors?.singleOrNull()?.symbol?.let { return it }
    val fragment = IrExternalPackageFragmentImpl(DescriptorlessExternalPackageFragmentSymbol(), classId.packageFqName)
    val stringArray = irBuiltIns.arrayClass.typeWith(irBuiltIns.stringType)
    val stub = irFactory.buildClass {
        kind = ClassKind.ANNOTATION_CLASS
        name = classId.shortClassName
        origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB
    }.apply {
        parent = fragment
        createThisReceiverParameter()
        superTypes = listOf(irBuiltIns.annotationClass.owner.defaultType)
        annotations = listOfNotNull(binaryRetention())
        addConstructor {
            isPrimary = true
            origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB
        }.apply {
            for (parameter in arrayParameters) addValueParameter(parameter, stringArray)
        }
    }
    return stub.constructors.single().symbol
}

/** `@Retention(AnnotationRetention.BINARY)`, what the real annotations declare: kept out of runtime reflection. */
private fun IrPluginContext.binaryRetention(): IrAnnotation? {
    val finder = finderForBuiltins()
    val retention = finder.findClass(StandardClassIds.Annotations.Retention) ?: return null
    val policy = finder.findClass(StandardClassIds.AnnotationRetention) ?: return null
    val binary = policy.owner.declarations.filterIsInstance<IrEnumEntry>().firstOrNull { it.name.asString() == "BINARY" } ?: return null
    return IrAnnotationImpl.fromSymbolOwner(UNDEFINED_OFFSET, UNDEFINED_OFFSET, retention.owner.defaultType, retention.owner.constructors.single().symbol, 0).apply {
        arguments[0] = IrGetEnumValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, policy.owner.defaultType, binary.symbol)
    }
}

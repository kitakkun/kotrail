package com.kitakkun.kotrail.fir.native.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirEqualityOperatorCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.utils.isFinal
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.isNothingOrNullableNothing
import org.jetbrains.kotlin.fir.types.isNullableNothing
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.isSubpackageOf

/**
 * Names of the Kotlin/Native Objective-C interop. They resolve only in a native compilation
 * that links a Darwin platform library; elsewhere every lookup is `null` and the checkers stay
 * quiet.
 */
object ObjCNames {
    val INTEROP_PACKAGE = FqName("kotlinx.cinterop")
    val OBJC_OBJECT = ClassId(INTEROP_PACKAGE, Name.identifier("ObjCObject"))

    /** Carried by every class and protocol that cinterop generated from Objective-C. */
    val EXTERNAL_OBJC_CLASS = ClassId(INTEROP_PACKAGE, Name.identifier("ExternalObjCClass"))

    /** The package of the compiler's forward declarations of Objective-C classes and protocols. */
    val FORWARD_DECLARATIONS = FqName("objcnames")
    val WEAK_REFERENCE = ClassId(FqName("kotlin.native.ref"), Name.identifier("WeakReference"))
}

/**
 * Whether values of this type are Objective-C objects: `NSObject` and its subclasses, and every
 * Objective-C protocol, all of which extend `kotlinx.cinterop.ObjCObject`.
 */
internal fun ConeKotlinType.isObjCObject(session: FirSession): Boolean {
    val objcObject = session.symbolProvider.getClassLikeSymbolByClassId(ObjCNames.OBJC_OBJECT) ?: return false
    return fullyExpandedType(session).isSubtypeOf(objcObject.constructType(isMarkedNullable = true), session)
}

/**
 * Reports `===` and `!==` on Objective-C objects. Kotlin/Native gives an Objective-C object a
 * fresh Kotlin wrapper every time it crosses into Kotlin, so wrapper identity says nothing
 * about object identity: two wrappers of one `UIWindow` compare `!==`. `==` calls `isEqual:`,
 * whose `NSObject` default is pointer equality, and `objcPtr()` compares addresses outright.
 */
object ObjCIdentityChecker : FirEqualityOperatorCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirEqualityOperatorCall) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.NATIVE_OBJC_IDENTITY)) return
        if (expression.operation != FirOperation.IDENTITY && expression.operation != FirOperation.NOT_IDENTITY) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val session = context.session
        if (expression.arguments.any { it.resolvedType.isNullableNothing }) return
        val objc = expression.arguments.firstOrNull { it.resolvedType.isObjCObject(session) } ?: return
        reportKotrail(
            source,
            KotrailDiagnostics.OBJC_IDENTITY_COMPARISON,
            expression.operation.operator,
            objc.resolvedType.fullyExpandedType().toRegularClassSymbol()?.name?.asString() ?: "an Objective-C object",
        )
    }
}

/**
 * Reports a `kotlin.native.ref.WeakReference` created for an Objective-C object. The weak
 * reference tracks the Kotlin wrapper, which the runtime collects as soon as nothing in Kotlin
 * holds it, while the Objective-C object lives on; the reference then answers `null` for a
 * live object. Hold the object strongly, or hold its address from `objcPtr()`.
 *
 * Only an object of a class defined in Objective-C comes in such a wrapper. An instance of a
 * Kotlin class that extends `NSObject` or implements a protocol is a Kotlin object whose Kotlin
 * side owns it, so a weak reference to it behaves as on any Kotlin object and is not reported.
 * The referent's class is taken from the argument's type, which a smart cast narrows; when that
 * type does not settle it and the argument reads a `val` whose value is its initializer, from
 * the initializer's type, one hop and no further; and last from the type argument.
 */
object ObjCWeakReferenceChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.NATIVE_OBJC_IDENTITY)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val callee = expression.calleeReference.toResolvedCallableSymbol() as? FirConstructorSymbol ?: return
        if (callee.callableId.classId != ObjCNames.WEAK_REFERENCE) return
        val session = context.session
        val referent = expression.resolvedType.typeArguments.firstOrNull()?.type ?: return
        if (!referent.isObjCObject(session)) return
        val argument = expression.arguments.firstOrNull()?.unwrapArgument()
        if (argument != null && holdsKotlinObject(argument, session)) return
        if (referent.isKotlinObjCClass(session)) return
        reportKotrail(
            source,
            KotrailDiagnostics.OBJC_WEAK_REFERENCE,
            referent.fullyExpandedType().toRegularClassSymbol()?.name?.asString() ?: "an Objective-C object",
        )
    }

    /**
     * Whether [argument] is known to be an instance of an Objective-C class defined in Kotlin:
     * by its own type, or by the type of the initializer of the `val` it reads.
     */
    context(context: CheckerContext)
    private fun holdsKotlinObject(argument: FirExpression, session: FirSession): Boolean {
        if (argument.resolvedType.isKotlinObjCClass(session)) return true
        val initializer = argument.valInitializer(session) ?: return false
        return initializer.resolvedType.isKotlinObjCClass(session)
    }

    /**
     * The initializer of the `val` this expression reads, when that is the value the read
     * returns: a local `val`, or a final `val` declared in the file being checked, in both cases
     * without a delegate or a getter body. An initializer that reads another `val` is not
     * followed further.
     */
    context(context: CheckerContext)
    private fun FirExpression.valInitializer(session: FirSession): FirExpression? {
        val read = (if (this is FirSmartCastExpression) originalExpression else this) as? FirPropertyAccessExpression ?: return null
        val property = read.calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol ?: return null
        if (property.isVar || property.hasDelegate || property.getterSymbol?.hasBody == true) return null
        if (!property.isLocal) {
            val file = context.containingFileSymbol ?: return null
            if (!property.isFinal || session.firProvider.getFirCallableContainerFile(property)?.symbol != file) return null
        }
        return property.resolvedInitializer
    }

    /**
     * Whether values of this type are instances of an Objective-C class defined in Kotlin: a
     * subtype of `ObjCObject` that neither comes from the interop library nor carries
     * `@ExternalObjCClass`, itself or through a class it is nested in, which is the test the
     * compiler applies. Supertypes do not count: a Kotlin subclass of `NSObject` has no such
     * annotation of its own. Kotlin/Native admits only final classes and objects as Kotlin
     * subtypes of Objective-C types, so such a type names the object's exact class.
     */
    private fun ConeKotlinType.isKotlinObjCClass(session: FirSession): Boolean {
        val expanded = fullyExpandedType(session)
        if (expanded.isNothingOrNullableNothing || !expanded.isObjCObject(session)) return false
        val symbol = expanded.toRegularClassSymbol(session) ?: return false
        val packageName = symbol.classId.packageFqName
        if (packageName == ObjCNames.INTEROP_PACKAGE || packageName.isSubpackageOf(ObjCNames.FORWARD_DECLARATIONS)) return false
        if (symbol.hasAnnotation(ObjCNames.EXTERNAL_OBJC_CLASS, session)) return false
        return generateSequence(symbol.classId.outerClassId) { it.outerClassId }.none { outer ->
            session.symbolProvider.getClassLikeSymbolByClassId(outer)?.hasAnnotation(ObjCNames.EXTERNAL_OBJC_CLASS, session) == true
        }
    }
}

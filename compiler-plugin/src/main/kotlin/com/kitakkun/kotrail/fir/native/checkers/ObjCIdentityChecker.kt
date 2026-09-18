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
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.isNullableNothing
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Names of the Kotlin/Native Objective-C interop. They resolve only in a native compilation
 * that links a Darwin platform library; elsewhere every lookup is `null` and the checkers stay
 * quiet.
 */
object ObjCNames {
    val OBJC_OBJECT = ClassId(FqName("kotlinx.cinterop"), Name.identifier("ObjCObject"))
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
        reportKotrail(
            source,
            KotrailDiagnostics.OBJC_WEAK_REFERENCE,
            referent.fullyExpandedType().toRegularClassSymbol()?.name?.asString() ?: "an Objective-C object",
        )
    }
}

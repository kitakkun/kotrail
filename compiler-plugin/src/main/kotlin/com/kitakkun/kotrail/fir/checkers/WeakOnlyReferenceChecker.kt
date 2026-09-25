@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirAnonymousObjectExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isNullableNothing
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * Reports a weak reference that is the only reference to what it wraps:
 *
 * ```kotlin
 * bus.subscribe(WeakReference { event -> handle(event) })   // reported: the lambda is collected at once
 * WeakReference(object : Listener { ... })                   // reported
 * val listener = Listener()
 * registry.add(WeakReference(listener))                      // reported: nothing else holds listener
 *
 * WeakReference(this.listener)                               // fine: a property holds it
 * WeakReference(listener)                                    // fine: listener is also used elsewhere
 * ```
 *
 * A weak reference keeps nothing alive; it only lets code see an object while something else
 * keeps it. Wrapping a lambda, an anonymous object, or a fresh instance that no property, no
 * collection and no other local holds leaves the object to the next collection, after which
 * the reference is empty and the listener silently stops. This is the bug that follows a "make
 * it weak" fix. What counts as a weak reference is `weakOnlyReference.types`, subtypes included.
 * A local counts as held elsewhere when the enclosing function reads it anywhere but in the
 * wrapping call; that is a conservative reading, so a local passed on before being wrapped is
 * not reported.
 */
object WeakOnlyReferenceChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.WEAK_ONLY_REFERENCE)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val session = context.session
        if (expression.calleeReference.toResolvedCallableSymbol() !is FirConstructorSymbol) return
        if (config.weakOnlyReference.types.none { expression.resolvedType.isSubtypeOf(ClassId.topLevel(FqName(it)), session) }) return
        val referent = expression.argumentList.arguments.firstOrNull() ?: return

        val what = referent.freshObject(session) ?: referent.localHeldNowhereElse(context, session) ?: return
        reportKotrail(source, KotrailDiagnostics.WEAK_REFERENCE_TO_FRESH_OBJECT, what)
    }

    /** What a freshly created argument is, or null when it is not one. */
    private fun FirExpression.freshObject(session: FirSession): String? = when (this) {
        is FirAnonymousFunctionExpression -> "a lambda"
        is FirAnonymousObjectExpression -> "an anonymous object"
        is FirFunctionCall -> {
            val callee = calleeReference.toResolvedCallableSymbol()
            // A constructor, or a SAM constructor (`Listener { }`), makes a fresh instance.
            if (callee is FirConstructorSymbol || callee?.origin == FirDeclarationOrigin.SamConstructor) "a new ${resolvedType.fullyExpandedType(session).renderReadable()}" else null
        }
        else -> null
    }

    /** A local `val` initialized with a fresh object and read nowhere else in the enclosing function. */
    private fun FirExpression.localHeldNowhereElse(context: CheckerContext, session: FirSession): String? {
        val access = this as? FirPropertyAccessExpression ?: return null
        val symbol = access.calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol ?: return null
        if (!symbol.isLocal || symbol.isVar) return null
        // A local's initializer is read from its declaration: resolving it through the symbol would start a lazy resolve on a local.
        val fresh = symbol.fir.initializer?.freshObject(session) ?: return null
        val function = context.containingElements.lastOrNull { it is FirFunction } as? FirFunction ?: return null
        val counter = ReadCounter(symbol)
        function.body?.accept(counter)
        return if (counter.reads <= 1) "${symbol.name.asString()}, $fresh that nothing else holds" else null
    }

    private class ReadCounter(private val target: FirPropertySymbol) : FirVisitorVoid() {
        var reads = 0

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            if (propertyAccessExpression.calleeReference.toResolvedCallableSymbol() == target) reads++
            propertyAccessExpression.acceptChildren(this)
        }
    }

    private fun ConeKotlinType.isSubtypeOf(classId: ClassId, session: FirSession): Boolean {
        val expanded = fullyExpandedType(session)
        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) ?: return false
        val superType = classId.constructClassLikeType(Array(symbol.typeParameterSymbols.size) { ConeStarProjection }, isMarkedNullable = true)
        return !expanded.isNullableNothing && AbstractTypeChecker.isSubtypeOf(session.typeContext, expanded, superType)
    }
}

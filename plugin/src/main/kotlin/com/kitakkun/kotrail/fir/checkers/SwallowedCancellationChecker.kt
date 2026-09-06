package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirTryExpressionChecker
import org.jetbrains.kotlin.fir.declarations.InlineStatus
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.expressions.FirCatch
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirThrowExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSuspendOrKSuspendFunctionType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Reports a catch clause that swallows `CancellationException` inside a suspend context:
 *
 * ```kotlin
 * suspend fun load() {
 *     try { fetch() } catch (e: Exception) { log(e) }   // cancellation never propagates
 * }
 * ```
 *
 * Coroutine cancellation is delivered as a `CancellationException`; a broad catch (`Throwable`,
 * `Exception`, `RuntimeException`, `IllegalStateException`) that does not rethrow keeps the
 * coroutine running after it was cancelled. The rule looks at the closest enclosing function
 * (a `suspend` function, a lambda of a suspend function type, or an inline lambda inside one)
 * and reports the first catch clause that would receive the cancellation. It stays quiet when
 * that clause catches `CancellationException` explicitly, contains any `throw`, or calls
 * `kotlinx.coroutines.ensureActive`, and when the try is not in a suspend context at all.
 */
object SwallowedCancellationChecker : FirTryExpressionChecker(MppCheckerKind.Common) {
    private val coroutinesPackage = FqName("kotlinx.coroutines")
    private val ensureActiveName = Name.identifier("ensureActive")

    private val cancellationClassIds: Set<ClassId> = setOf(
        ClassId(coroutinesPackage, Name.identifier("CancellationException")),
        ClassId(FqName("java.util.concurrent"), Name.identifier("CancellationException")),
    )

    /** Every proper supertype of `CancellationException`, in both Kotlin and JVM spelling. */
    private val cancellationSupertypeClassIds: Set<ClassId> = setOf(
        ClassId(FqName("kotlin"), Name.identifier("Throwable")),
        ClassId(FqName("kotlin"), Name.identifier("Exception")),
        ClassId(FqName("kotlin"), Name.identifier("RuntimeException")),
        ClassId(FqName("kotlin"), Name.identifier("IllegalStateException")),
        ClassId(FqName("java.lang"), Name.identifier("Throwable")),
        ClassId(FqName("java.lang"), Name.identifier("Exception")),
        ClassId(FqName("java.lang"), Name.identifier("RuntimeException")),
        ClassId(FqName("java.lang"), Name.identifier("IllegalStateException")),
    )

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirTryExpression) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.NO_SWALLOWED_CANCELLATION)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val session = context.session
        if (!context.isSuspendContext(session)) return

        // Only the first clause that matches CancellationException ever sees it; later clauses are unreachable for it.
        val receivingClause = expression.catches.firstOrNull { it.catchesCancellation(session) } ?: return
        val catchSource = receivingClause.source ?: return
        if (receivingClause.isExplicitCancellationCatch(session)) return
        if (receivingClause.block.rethrowsOrChecksCancellation()) return

        val severity = session.kotrailConfig.severity(KotrailRule.NO_SWALLOWED_CANCELLATION)
        reporter.reportOn(catchSource, KotrailDiagnostics.SWALLOWED_CANCELLATION.at(severity))
    }

    /**
     * Whether the code at this point runs inside a coroutine: the closest enclosing function is
     * `suspend`, or a lambda with a suspend function type. Inline lambdas run in the caller's
     * coroutine, so the search continues outward through them.
     */
    private fun CheckerContext.isSuspendContext(session: FirSession): Boolean {
        for (symbol in containingDeclarations.asReversed()) {
            when (symbol) {
                is FirAnonymousFunctionSymbol -> {
                    if (symbol.isSuspend) return true
                    if (symbol.resolvedTypeRef.coneType.isSuspendOrKSuspendFunctionType(session)) return true
                    if (symbol.inlineStatus == InlineStatus.Inline) continue
                    return false
                }
                is FirFunctionSymbol<*> -> return symbol.isSuspend
                is FirClassSymbol<*> -> return false
                else -> continue
            }
        }
        return false
    }

    private fun FirCatch.catchesCancellation(session: FirSession): Boolean {
        val classId = parameter.returnTypeRef.coneType.fullyExpandedType(session).classId ?: return false
        return classId in cancellationClassIds || classId in cancellationSupertypeClassIds
    }

    private fun FirCatch.isExplicitCancellationCatch(session: FirSession): Boolean {
        val type = parameter.returnTypeRef.coneType
        return type.classId in cancellationClassIds || type.fullyExpandedType(session).classId in cancellationClassIds
    }

    private fun FirElement.rethrowsOrChecksCancellation(): Boolean {
        val visitor = RethrowVisitor()
        accept(visitor)
        return visitor.found
    }

    /** Finds any `throw` or any `kotlinx.coroutines.ensureActive()` call, both of which let cancellation escape. */
    private class RethrowVisitor : FirVisitorVoid() {
        var found = false

        override fun visitElement(element: FirElement) {
            if (found) return
            element.acceptChildren(this)
        }

        override fun visitThrowExpression(throwExpression: FirThrowExpression) {
            found = true
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            val callableId = functionCall.calleeReference.toResolvedNamedFunctionSymbol()?.callableId
            if (callableId != null && callableId.packageName == coroutinesPackage && callableId.callableName == ensureActiveName) {
                found = true
                return
            }
            functionCall.acceptChildren(this)
        }
    }
}

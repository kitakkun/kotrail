package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirAnonymousFunctionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirQualifiedAccessExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.utils.isInner
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirReceiverParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirThisOwnerSymbol

/**
 * Reports a bare name that resolves on an outer implicit receiver while a nearer one is in scope:
 *
 * ```kotlin
 * class Screen {
 *     fun title(): String = "t"
 *     fun bind(view: View) {
 *         view.apply {
 *             text = title()          // reported: title() is Screen's, but View is the nearest this
 *             text = this@Screen.title()   // fine
 *         }
 *     }
 * }
 * ```
 *
 * With two receivers in scope, a bare `title()` reads as the nearer one's until the reader
 * checks. Qualifying the outer access (`this@Screen.title()`) says where it belongs. Receivers
 * are the dispatch receiver of an enclosing class or object, the extension receiver of an
 * enclosing function or property, and the receiver of an enclosing lambda (`apply`, `with`,
 * `buildString`); a non-inner nested class starts over. `@DslMarker` receivers already forbid
 * the outer access at the language level and are never reported here.
 */
object OuterImplicitReceiverChecker : FirQualifiedAccessExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirQualifiedAccessExpression) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.IMPLICIT_RECEIVERS)) return
        if (!config.implicitReceivers.qualifyOuter) return
        if (expression is FirThisReceiverExpression) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return

        val implicit = listOfNotNull(expression.dispatchReceiver, expression.extensionReceiver)
            .filterIsInstance<FirThisReceiverExpression>()
            .filter { it.isImplicit }
        if (implicit.isEmpty()) return
        val nearest = context.nearestReceivers() ?: return
        for (receiver in implicit) {
            val bound = (receiver.calleeReference as? FirThisReference)?.boundSymbol ?: continue
            if (bound in nearest) continue
            val name = expression.calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: continue
            reportKotrail(source, KotrailDiagnostics.IMPLICIT_RECEIVER_FROM_OUTER_SCOPE, name, bound.describe())
            return
        }
    }

    /** The receivers of the innermost enclosing declaration that has any, or `null` when none is in scope. */
    private fun CheckerContext.nearestReceivers(): Set<FirThisOwnerSymbol<*>>? {
        for (symbol in containingDeclarations.asReversed()) {
            val receivers = symbol.introducedReceivers()
            if (receivers.isNotEmpty()) return receivers
        }
        return null
    }

    private fun FirThisOwnerSymbol<*>.describe(): String = when (this) {
        is FirClassSymbol<*> -> "this@${classId.shortClassName.asString()}"
        is FirReceiverParameterSymbol -> when (val owner = containingDeclarationSymbol) {
            is FirNamedFunctionSymbol -> "this@${owner.name.asString()}"
            is FirPropertySymbol -> "this@${owner.name.asString()}"
            else -> "the receiver of an outer lambda"
        }
        else -> "an outer receiver"
    }
}

/**
 * Caps how many implicit receivers are in scope at once, reporting the lambda that brings the
 * count past `implicitReceivers.maxDepth`. Off unless the limit is set: every method that uses
 * `apply` already has two, and where the line goes is a project's decision.
 */
object ImplicitReceiverDepthChecker : FirAnonymousFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirAnonymousFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.IMPLICIT_RECEIVERS)) return
        val limit = config.implicitReceivers.maxDepth
        if (limit <= 0) return
        if (declaration.receiverParameter == null) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return

        val depth = context.receiverDepth(declaration.symbol)
        if (depth <= limit) return
        reportKotrail(source, KotrailDiagnostics.TOO_MANY_IMPLICIT_RECEIVERS, "$depth implicit receivers in scope (limit $limit)")
    }

    /**
     * Receivers introduced by every enclosing declaration out to the nearest non-inner class, plus
     * [lambda]'s own. The context may or may not list the lambda itself; it is counted once either way.
     */
    private fun CheckerContext.receiverDepth(lambda: FirAnonymousFunctionSymbol): Int {
        var depth = lambda.introducedReceivers().size
        for (symbol in containingDeclarations.asReversed()) {
            if (symbol == lambda) continue
            depth += symbol.introducedReceivers().size
            if (symbol is FirRegularClassSymbol && !symbol.isInner) break
        }
        return depth
    }
}

/** The implicit receivers a declaration puts in scope for the code inside it. */
private fun FirBasedSymbol<*>.introducedReceivers(): Set<FirThisOwnerSymbol<*>> = when (this) {
    is FirClassSymbol<*> -> setOf(this)
    is FirCallableSymbol<*> -> setOfNotNull(receiverParameterSymbol)
    else -> emptySet()
}

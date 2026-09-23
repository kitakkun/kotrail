package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
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
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.scopes.getFunctions
import org.jetbrains.kotlin.fir.scopes.getProperties
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirReceiverParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirThisOwnerSymbol
import org.jetbrains.kotlin.name.Name

/**
 * Reports a bare name that two implicit receivers in scope could supply:
 *
 * ```kotlin
 * class Screen {
 *     var text: String = ""
 *     fun bind(view: View) {
 *         view.apply {
 *             text = "hello"          // reported: View.text, but Screen also has a text
 *             this.text = "hello"     // fine
 *             title()                 // fine: only Screen has a title
 *         }
 *     }
 * }
 * ```
 *
 * The compiler picks the nearest receiver that has the member; a reader who has the other one in
 * mind reads the line wrong, and an assignment goes to the wrong object without a diagnostic.
 * Qualifying the access (`this.text`, `this@Screen.text`) settles it. A name that only one
 * receiver has is not reported, however far out that receiver is: a class member used inside
 * `runBlocking { }`, `apply { }`, or a builder is the ordinary way to write Kotlin. Receivers are
 * the dispatch receiver of an enclosing class or object, the extension receiver of an enclosing
 * function or property, and the receiver of an enclosing lambda; a non-inner nested class starts
 * over. Left alone: two receivers of one type (`Row { Row { } }`, a builder inside itself), where
 * the nearest one is what everybody means; a member extension, which takes both receivers as one
 * declaration; and `toString`, `hashCode`, `equals`, which every receiver has.
 */
object AmbiguousImplicitReceiverChecker : FirQualifiedAccessExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirQualifiedAccessExpression) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.IMPLICIT_RECEIVERS)) return
        if (!config.implicitReceivers.qualifyAmbiguous) return
        if (expression is FirThisReceiverExpression) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return

        val implicit = listOfNotNull(expression.dispatchReceiver, expression.extensionReceiver)
            .filterIsInstance<FirThisReceiverExpression>()
            .filter { it.isImplicit }
        if (implicit.isEmpty()) return
        val callee = expression.calleeReference.toResolvedCallableSymbol() ?: return
        // A member extension takes both receivers at once: one declaration, nothing to choose between.
        if (callee.receiverParameterSymbol != null && callee.dispatchReceiverType != null) return
        // Every receiver has these; that another one has them too says nothing.
        if (callee.name in ANY_MEMBERS) return
        val inScope = context.receiversInScope()
        if (inScope.size < 2) return
        val session = context.session
        for (receiver in implicit) {
            val bound = (receiver.calleeReference as? FirThisReference)?.boundSymbol ?: continue
            val boundClass = bound.classSymbol(session)
            val other = inScope.firstOrNull { candidate ->
                candidate != bound &&
                    // Nesting a scope in another of the same type (`Row { Row { } }`, a builder in a builder) means the nearest one.
                    candidate.classSymbol(session) != boundClass &&
                    candidate.hasMemberNamed(callee.name, session, context.scopeSession)
            } ?: continue
            reportKotrail(
                source,
                KotrailDiagnostics.IMPLICIT_RECEIVER_AMBIGUOUS,
                callee.name.asString(),
                "${bound.describe()} (${other.describe()} has a '${callee.name.asString()}' too)",
            )
            return
        }
    }

    /** Every implicit receiver in scope, innermost first, out to the nearest non-inner class. */
    private fun CheckerContext.receiversInScope(): List<FirThisOwnerSymbol<*>> {
        val receivers = mutableListOf<FirThisOwnerSymbol<*>>()
        for (symbol in containingDeclarations.asReversed()) {
            receivers += symbol.introducedReceivers()
            if (symbol is FirRegularClassSymbol && !symbol.isInner) break
        }
        return receivers
    }

    private val ANY_MEMBERS = setOf(Name.identifier("toString"), Name.identifier("hashCode"), Name.identifier("equals"))

    private fun FirThisOwnerSymbol<*>.classSymbol(session: FirSession): FirClassSymbol<*>? = when (this) {
        is FirClassSymbol<*> -> this
        is FirReceiverParameterSymbol -> resolvedType.fullyExpandedType(session).toRegularClassSymbol(session)
        else -> null
    }

    /** Whether the receiver's type declares or inherits a function or property called [name]. */
    private fun FirThisOwnerSymbol<*>.hasMemberNamed(name: Name, session: FirSession, scopeSession: ScopeSession): Boolean {
        val classSymbol = classSymbol(session) ?: return false
        val scope = classSymbol.unsubstitutedScope(session, scopeSession, withForcedTypeCalculator = false, memberRequiredPhase = null)
        return scope.getFunctions(name).isNotEmpty() || scope.getProperties(name).isNotEmpty()
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

/** How to spell the receiver in a qualification: `this@Screen`, `this@show`, `this@apply`. */
private fun FirThisOwnerSymbol<*>.describe(): String = when (this) {
    is FirClassSymbol<*> -> "this@${classId.shortClassName.asString()}"
    is FirReceiverParameterSymbol -> when (val owner = containingDeclarationSymbol) {
        is FirNamedFunctionSymbol -> "this@${owner.name.asString()}"
        is FirPropertySymbol -> "this@${owner.name.asString()}"
        is FirAnonymousFunctionSymbol -> owner.label?.name?.let { "this@$it" } ?: "the receiver of the enclosing lambda"
        else -> "the outer receiver"
    }
    else -> "the outer receiver"
}

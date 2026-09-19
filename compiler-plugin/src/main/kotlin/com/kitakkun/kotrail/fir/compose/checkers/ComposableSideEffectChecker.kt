package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirAnonymousObjectExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Reports a call that starts work directly in a composable body:
 *
 * ```kotlin
 * @Composable
 * fun Screen(scope: CoroutineScope, viewModel: ScreenViewModel) {
 *     scope.launch { viewModel.load() }             // reported: runs on every recomposition
 *     LaunchedEffect(Unit) { viewModel.load() }     // fine: an effect
 *     Button(onClick = { scope.launch { } }) { }    // fine: an event handler
 * }
 * ```
 *
 * A composable body runs whenever Compose recomposes it, so work started there is repeated at
 * times the author never chose. The rule recognizes a call by what it returns
 * (`compose.noSideEffectInComposition.types`, `Job` and `Deferred` by default: `launch`, `async`,
 * `launchIn`) or by name (`functions`). Only code that runs during composition is inspected:
 * the body and the lambdas of inline non-composable functions (`forEach`, `let`, `apply`), never
 * a lambda handed to a composable (`remember`, `LaunchedEffect`, a content slot) or to a
 * callback.
 */
object ComposableSideEffectChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_NO_SIDE_EFFECT_IN_COMPOSITION)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (!declaration.symbol.isComposable(context.session)) return
        val body = declaration.body ?: return

        val walker = CompositionWalker(
            session = context.session,
            types = config.compose.sideEffectTypes.toSet(),
            functions = config.compose.sideEffectFunctions.toSet(),
        ) { call, name ->
            val target = call.calleeReference.source ?: call.source ?: return@CompositionWalker
            reportKotrail(target, KotrailDiagnostics.COMPOSABLE_SIDE_EFFECT_IN_COMPOSITION, name)
        }
        body.accept(walker)
    }

    /** Visits what runs during composition, stopping at every lambda except those an inline non-composable callee runs in place. */
    private class CompositionWalker(
        private val session: FirSession,
        private val types: Set<String>,
        private val functions: Set<String>,
        private val report: (FirFunctionCall, String) -> Unit,
    ) : FirVisitorVoid() {
        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitAnonymousFunctionExpression(anonymousFunctionExpression: FirAnonymousFunctionExpression) {}

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {}

        override fun visitAnonymousObjectExpression(anonymousObjectExpression: FirAnonymousObjectExpression) {}

        override fun visitNamedFunction(namedFunction: FirNamedFunction) {}

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            val callee = functionCall.calleeReference.toResolvedNamedFunctionSymbol()
            if (callee != null && functionCall.startsWork(callee)) {
                report(functionCall, callee.name.asString())
            }
            functionCall.explicitReceiver?.accept(this)
            val inlineLambdas = if (callee != null) functionCall.inlineLambdaArguments(callee) else emptySet()
            for (argument in functionCall.argumentList.arguments) {
                val unwrapped = argument.unwrapArgument()
                if (unwrapped is FirAnonymousFunctionExpression) {
                    if (unwrapped in inlineLambdas) unwrapped.anonymousFunction.body?.accept(this)
                } else {
                    argument.accept(this)
                }
            }
        }

        /** By name, or by the callee's declared return type: `remember { launch { } }` declares `T`, not `Job`. */
        private fun FirFunctionCall.startsWork(callee: FirNamedFunctionSymbol): Boolean {
            val fqn = callee.callableId?.asSingleFqName()?.asString()
            if (fqn != null && fqn in functions) return true
            val declared = callee.resolvedReturnType.fullyExpandedType(session).classId?.asSingleFqName()?.asString() ?: return false
            return declared in types
        }

        /** Lambdas the callee runs in place: arguments of an inline, non-composable function bound to inlinable parameters. */
        private fun FirFunctionCall.inlineLambdaArguments(callee: FirNamedFunctionSymbol): Set<FirAnonymousFunctionExpression> {
            if (!callee.isInline || callee.isComposable(session)) return emptySet()
            val mapping = resolvedArgumentMapping ?: return emptySet()
            return mapping.entries.mapNotNullTo(HashSet()) { (argument, parameter) ->
                (argument.unwrapArgument() as? FirAnonymousFunctionExpression)?.takeUnless { parameter.isNoinline }
            }
        }
    }
}

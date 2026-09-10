package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.insets.WindowInsetsNames
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Limits how deeply composable calls nest inside one composable body. Depth grows by one each
 * time a composable call sits inside a lambda passed to a `@Composable` parameter of another
 * composable call (a content slot). Lambdas passed to ordinary parameters, such as
 * `LazyColumn`'s `content` or `remember`'s calculation, do not add depth.
 *
 * Only the first level past the limit is reported so that one deep tree yields one error.
 */
object ComposableNestingChecker : FirSimpleFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_NESTING)) return
        val max = config.compose.maxNesting
        if (max <= 0) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (!declaration.symbol.hasAnnotation(WindowInsetsNames.COMPOSABLE, context.session)) return
        val body = declaration.body ?: return

        val walker = NestingWalker(context.session, max) { call, depth ->
            val target = call.calleeReference.source ?: call.source ?: return@NestingWalker
            reportKotrail(
                target,
                KotrailDiagnostics.COMPOSABLE_NESTING_TOO_DEEP,
                depth.toString(),
                max.toString(),
            )
        }
        body.accept(walker)
    }

    private class NestingWalker(
        private val session: FirSession,
        private val max: Int,
        private val report: (FirFunctionCall, Int) -> Unit,
    ) : FirVisitorVoid() {
        private var depth = 0

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            val callee = functionCall.calleeReference.toResolvedNamedFunctionSymbol()
            if (callee == null || !callee.hasAnnotation(WindowInsetsNames.COMPOSABLE, session)) {
                functionCall.acceptChildren(this)
                return
            }
            val callDepth = depth + 1
            if (callDepth == max + 1) {
                report(functionCall, callDepth)
            }

            val composableSlots = functionCall.composableLambdaArguments()
            functionCall.explicitReceiver?.accept(this)
            for (argument in functionCall.argumentList.arguments) {
                val unwrapped = argument.unwrapArgument()
                if (unwrapped in composableSlots) {
                    depth = callDepth
                    unwrapped.accept(this)
                    depth = callDepth - 1
                } else {
                    argument.accept(this)
                }
            }
        }

        /** Lambda arguments bound to parameters whose type carries `@Composable`. */
        private fun FirFunctionCall.composableLambdaArguments(): Set<FirExpression> {
            val mapping = resolvedArgumentMapping ?: return emptySet()
            return mapping.entries
                .filter { (argument, parameter) ->
                    argument.unwrapArgument() is FirAnonymousFunctionExpression &&
                        parameter.returnTypeRef.coneType.customAnnotations.any {
                            it.toAnnotationClassId(session) == WindowInsetsNames.COMPOSABLE
                        }
                }
                .map { it.key.unwrapArgument() }
                .toSet()
        }
    }
}

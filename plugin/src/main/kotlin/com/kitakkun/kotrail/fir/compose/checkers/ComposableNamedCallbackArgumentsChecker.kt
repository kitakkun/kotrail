package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.isSuspendOrKSuspendFunctionType
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.types.receiverType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.text

/**
 * Inside a composable, a callback passed to another composable must be a named argument:
 *
 * ```kotlin
 * IconButton { viewModel.retry() }             // reported: reads like a content slot
 * IconButton(onClick = { viewModel.retry() })  // what the rule asks for
 * ```
 *
 * The declaration-side rule (`compose.noTrailingCallback`) keeps callbacks out of the trailing
 * position, but library composables and legacy APIs cannot always be changed. This rule closes
 * the gap at the call site: when the trailing-lambda syntax would bind to a non-composable
 * function-type parameter (without a receiver) that returns `Unit`, the argument must be named.
 *
 * Stays quiet outside composable functions, for non-composable callees, when the parameter is a
 * `@Composable` lambda (a real content slot), a `suspend` lambda, a receiver lambda (`LazyColumn {}`),
 * or returns a value (`remember`, `derivedStateOf`), and for callees in `compose.trailingLambdaAllowedPackages` (the Compose
 * runtime effect APIs by default).
 */
object ComposableNamedCallbackArgumentsChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_NAMED_CALLBACK_ARGUMENTS)) return
        val callSource = expression.source ?: return
        if (callSource.kind is KtFakeSourceElementKind) return
        val session = context.session
        // Lambdas inside the composable (Column { ... }) belong to it; look for the closest named function.
        val enclosing = context.containingDeclarations.lastOrNull { it is FirNamedFunctionSymbol } as? FirNamedFunctionSymbol ?: return
        if (!enclosing.isComposable(session)) return
        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        if (!callee.isComposable(session)) return
        val calleePackage = callee.callableId.packageName.asString()
        if (config.compose.trailingLambdaAllowedPackages.any { calleePackage == it || calleePackage.startsWith("$it.") }) return

        val mapping = expression.resolvedArgumentMapping ?: return
        val (argument, parameter) = mapping.entries.lastOrNull() ?: return
        val lambda = argument.unwrapArgument() as? FirAnonymousFunctionExpression ?: return
        val type = parameter.returnTypeRef.coneType
        if (!type.isSomeFunctionType(session)) return
        if (type.customAnnotations.any { it.toAnnotationClassId(session) == ComposeNames.COMPOSABLE }) return
        if (type.isSuspendOrKSuspendFunctionType(session)) return
        // DSL builders such as LazyColumn { items(...) } take a receiver lambda, which is not a callback.
        if (type.receiverType(session) != null) return
        if (type.functionReturnType()?.isUnit != true) return
        if (!isTrailingLambda(expression, lambda)) return

        val severity = config.severity(KotrailRule.COMPOSE_NAMED_CALLBACK_ARGUMENTS)
        reporter.reportOn(lambda.source ?: return, KotrailDiagnostics.COMPOSABLE_CALLBACK_AS_TRAILING_LAMBDA.at(severity), parameter.name.asString())
    }

    /** The return type of a function type is its last type argument. */
    private fun ConeKotlinType.functionReturnType(): ConeKotlinType? =
        (this as? ConeClassLikeType)?.typeArguments?.lastOrNull()?.type

    /**
     * A trailing lambda follows the argument parentheses (or the callee name when there are none),
     * so the non-blank character before it is `)` , an identifier character, or `>`. A lambda
     * inside the parentheses is preceded by `(`, `,`, or `=`.
     */
    private fun isTrailingLambda(call: FirFunctionCall, lambda: FirAnonymousFunctionExpression): Boolean {
        val callSource = call.source ?: return false
        val lambdaSource = lambda.source ?: return false
        val text = callSource.text ?: return false
        var index = lambdaSource.startOffset - callSource.startOffset - 1
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0) return false
        val before = text[index]
        return before == ')' || before == '>' || before.isLetterOrDigit() || before == '_' || before == '`'
    }
}

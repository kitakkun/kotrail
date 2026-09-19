package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.compose.isPreview
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.test.isTestFunction
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.types.ConstantValueKind

/**
 * Reports a string literal passed to a user-facing parameter of a composable:
 *
 * ```kotlin
 * Text("Submit")                                  // reported
 * Text(stringResource(Res.string.submit))         // fine
 * Text("$count")                                  // fine: a template is built from data
 * ```
 *
 * Text that reaches the screen from a literal cannot be translated. Which parameters count is
 * `compose.noHardcodedString.parameters` (`text`, `label`, `title`, ... by default); a literal
 * without a letter (`"•"`, `" "`) is punctuation rather than copy, and previews and tests are
 * left alone because their strings never ship. The rule is off by default: a project that does
 * not localize has no use for it.
 */
object ComposableHardcodedStringChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    private const val MAX_QUOTED_LENGTH = 40

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_NO_HARDCODED_STRING)) return
        val parameters = config.compose.hardcodedStringParameters
        if (parameters.isEmpty()) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val session = context.session
        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        if (!callee.isComposable(session)) return
        if (context.insidePreviewOrTest(config.test.annotations)) return

        val mapping = expression.resolvedArgumentMapping ?: return
        for ((argument, parameter) in mapping) {
            if (parameter.name.asString() !in parameters) continue
            val literal = argument.unwrapArgument() as? FirLiteralExpression ?: continue
            if (literal.kind != ConstantValueKind.String) continue
            val text = literal.value as? String ?: continue
            if (text.none { it.isLetter() }) continue
            val literalSource = literal.source ?: continue
            if (literalSource.kind is KtFakeSourceElementKind) continue
            reportKotrail(literalSource, KotrailDiagnostics.COMPOSABLE_HARDCODED_STRING, text.abbreviated(), parameter.name.asString())
        }
    }

    private fun CheckerContext.insidePreviewOrTest(testAnnotations: List<String>): Boolean =
        containingDeclarations.any { symbol ->
            symbol is FirNamedFunctionSymbol && (symbol.isPreview(session) || symbol.isTestFunction(session, testAnnotations))
        }

    private fun String.abbreviated(): String =
        if (length <= MAX_QUOTED_LENGTH) this else take(MAX_QUOTED_LENGTH - 1) + "…"
}

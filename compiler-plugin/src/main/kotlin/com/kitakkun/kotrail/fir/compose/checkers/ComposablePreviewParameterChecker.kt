@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.compose.isPreview
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.reportingAt
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId

/**
 * Asks previews that build the same model by hand, one state each, to take it as a
 * `@PreviewParameter`:
 *
 * ```kotlin
 * @Preview @Composable private fun UserCardPreview() { UserCard(User("Ada", Plan.Pro)) }      // reported together
 * @Preview @Composable private fun UserCardFreePreview() { UserCard(User("Grace", Plan.Free)) }
 *
 * @Preview @Composable
 * private fun UserCardPreview(@PreviewParameter(UserProvider::class) user: User) { UserCard(user) }  // fine
 * ```
 *
 * The states a component can be in are then scattered across previews and drift apart; a
 * `PreviewParameterProvider` lists them in one place, every preview shows all of them, and
 * screenshot tests get the same list. A single preview building a single state is left alone
 * (`minPreviews`, 2 by default): a provider there is indirection with nothing to gather. What
 * counts as a model is a constructor call of a class the project declares (`User(...)`,
 * `listOf(User(...))`, `UiState(items = ...)`), anywhere in an argument of a composable call
 * except inside a lambda; strings, numbers, enum entries, `Modifier` chains, classpath types,
 * and a stand-in whose name starts with `Preview` (`PreviewDataModel`, made for previews) are
 * not models. A preview that already takes a `@PreviewParameter` is left alone, and one finding
 * per preview is enough. Off by default: an established codebase previews by hand everywhere.
 */
object ComposablePreviewParameterChecker : FirFileChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val session = context.session
        val config = session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_PREVIEW_PARAMETER)) return
        val minPreviews = config.compose.previewParameterMinPreviews.coerceAtLeast(1)

        val previews = mutableListOf<Pair<FirNamedFunction, List<FirDeclaration>>>()
        collectPreviews(declaration.declarations, emptyList(), session, previews)
        val findings = previews.mapNotNull { (preview, enclosing) ->
            if (preview.source?.kind is KtFakeSourceElementKind) return@mapNotNull null
            if (preview.valueParameters.any { it.symbol.hasAnnotation(ComposeNames.PREVIEW_PARAMETER, session) }) return@mapNotNull null
            val body = preview.body ?: return@mapNotNull null
            val finder = InlineModelFinder(session)
            body.accept(finder)
            val found = finder.found ?: return@mapNotNull null
            Finding(preview, enclosing, found.argument, found.model)
        }
        val previewsPerModel = findings.groupingBy { it.model.classId }.eachCount()
        for (finding in findings) {
            if (previewsPerModel.getValue(finding.model.classId) < minPreviews) continue
            val source = finding.argument.source ?: continue
            reportingAt(finding.enclosing + finding.preview) {
                reportKotrail(source, KotrailDiagnostics.PREVIEW_MODEL_BUILT_INLINE, finding.model.classId.shortClassName.asString(), finding.preview.name.asString())
            }
        }
    }

    private class Finding(val preview: FirNamedFunction, val enclosing: List<FirDeclaration>, val argument: FirExpression, val model: FirRegularClassSymbol)

    private fun collectPreviews(declarations: List<FirDeclaration>, enclosing: List<FirDeclaration>, session: FirSession, into: MutableList<Pair<FirNamedFunction, List<FirDeclaration>>>) {
        for (declaration in declarations) {
            when (declaration) {
                is FirNamedFunction -> if (declaration.symbol.isPreview(session)) into += declaration to enclosing
                is FirRegularClass -> collectPreviews(declaration.declarations, enclosing + declaration, session, into)
                else -> {}
            }
        }
    }

    private class Found(val argument: FirExpression, val model: FirRegularClassSymbol)

    /** The first argument of a composable call that builds a project model inline, with the model's class. */
    private class InlineModelFinder(private val session: FirSession) : FirVisitorVoid() {
        var found: Found? = null

        override fun visitElement(element: FirElement) {
            if (found != null) return
            element.acceptChildren(this)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            if (found != null) return
            if (functionCall.calleeReference.toResolvedNamedFunctionSymbol()?.isComposable(session) == true) {
                for (argument in functionCall.argumentList.arguments) {
                    val model = argument.projectModelBuiltInline() ?: continue
                    found = Found(argument, model)
                    return
                }
            }
            functionCall.acceptChildren(this)
        }

        /** The first constructor call of a project class in this expression, lambdas excluded; a `Preview*` stand-in does not count. */
        private fun FirExpression.projectModelBuiltInline(): FirRegularClassSymbol? {
            var model: FirRegularClassSymbol? = null
            accept(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (model != null) return
                    element.acceptChildren(this)
                }

                override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {}

                override fun visitFunctionCall(functionCall: FirFunctionCall) {
                    if (model != null) return
                    val constructor = functionCall.calleeReference.toResolvedCallableSymbol() as? FirConstructorSymbol
                    val classSymbol = constructor?.takeIf { it.origin.fromSource }?.resolvedReturnType?.toRegularClassSymbol(session)
                    if (classSymbol != null && classSymbol.classKind == ClassKind.CLASS && !classSymbol.classId.isPreviewStandIn()) {
                        model = classSymbol
                        return
                    }
                    functionCall.acceptChildren(this)
                }
            })
            return model
        }

        private fun ClassId.isPreviewStandIn(): Boolean = shortClassName.asString().startsWith("Preview")
    }
}

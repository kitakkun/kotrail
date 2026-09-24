package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.compose.isPreview
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Asks a preview that builds the model it shows by hand to take it as a `@PreviewParameter`:
 *
 * ```kotlin
 * @Preview @Composable
 * private fun UserCardPreview() {
 *     UserCard(user = User("Ada", plan = Plan.Pro))          // reported: User(...) built inline
 * }
 *
 * @Preview @Composable
 * private fun UserCardPreview(@PreviewParameter(UserProvider::class) user: User) {
 *     UserCard(user = user)                                   // fine
 * }
 * ```
 *
 * A model built inline shows one state, and the next preview of the same composable builds
 * another by hand, so the states a component can be in are scattered across previews and drift
 * apart. A `PreviewParameterProvider` enumerates them in one place, every preview shows all of
 * them, and screenshot tests get the same list. What counts as a model is a constructor call of
 * a class the project declares (`User(...)`, `listOf(User(...))`, `UiState(items = ...)`),
 * anywhere in an argument of a composable call except inside a lambda; strings, numbers, enum
 * entries, `Modifier` chains and classpath types are not models. A preview that already takes a
 * `@PreviewParameter` is left alone: it has made its choice, and one finding per preview is
 * enough. Off by default: an established codebase previews by hand everywhere.
 */
object ComposablePreviewParameterChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val session = context.session
        if (!session.kotrailConfig.isEnabled(KotrailRule.COMPOSE_PREVIEW_PARAMETER)) return
        if (declaration.source?.kind is KtFakeSourceElementKind) return
        if (!declaration.symbol.isPreview(session)) return
        if (declaration.valueParameters.any { it.symbol.hasAnnotation(ComposeNames.PREVIEW_PARAMETER, session) }) return
        val body = declaration.body ?: return

        val finder = InlineModelFinder(session)
        body.accept(finder)
        val (argument, modelName) = finder.found ?: return
        val source = argument.source ?: return
        reportKotrail(source, KotrailDiagnostics.PREVIEW_MODEL_BUILT_INLINE, modelName, declaration.name.asString())
    }

    /** The first argument of a composable call that builds a project model inline, with the model's name. */
    private class InlineModelFinder(private val session: FirSession) : FirVisitorVoid() {
        var found: Pair<FirExpression, String>? = null

        override fun visitElement(element: FirElement) {
            if (found != null) return
            element.acceptChildren(this)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            if (found != null) return
            if (functionCall.calleeReference.toResolvedNamedFunctionSymbol()?.isComposable(session) == true) {
                for (argument in functionCall.argumentList.arguments) {
                    val model = argument.projectModelBuiltInline() ?: continue
                    found = argument to model
                    return
                }
            }
            functionCall.acceptChildren(this)
        }

        /** The class name of the first constructor call of a project class in this expression, lambdas excluded. */
        private fun FirExpression.projectModelBuiltInline(): String? {
            var name: String? = null
            accept(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (name != null) return
                    element.acceptChildren(this)
                }

                override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {}

                override fun visitFunctionCall(functionCall: FirFunctionCall) {
                    if (name != null) return
                    val constructor = functionCall.calleeReference.toResolvedCallableSymbol() as? FirConstructorSymbol
                    val classSymbol = constructor?.takeIf { it.origin.fromSource }?.resolvedReturnType?.toRegularClassSymbol(session)
                    if (classSymbol != null && classSymbol.classKind == ClassKind.CLASS) {
                        name = classSymbol.classId.shortClassName.asString()
                        return
                    }
                    functionCall.acceptChildren(this)
                }
            })
            return name
        }
    }
}

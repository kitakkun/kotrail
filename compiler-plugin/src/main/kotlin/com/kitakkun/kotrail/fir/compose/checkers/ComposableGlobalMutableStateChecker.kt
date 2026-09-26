package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.isComposable
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
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.isConst
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.declarations.utils.isLateInit
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirAnonymousObjectExpression
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.isNullLiteral
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Reports a composable that reads or writes a plain global `var`: a top-level one, or a member
 * of an `object` (a companion included).
 *
 * ```kotlin
 * var isDarkTheme = false
 * object Session { var user: User? = null }
 *
 * @Composable
 * fun Header() {
 *     if (isDarkTheme) DarkLogo() else Logo()       // reported: never recomposes when it changes
 *     Text(Session.user?.name ?: "guest")           // reported
 *     Session.user = null                           // reported: assigned during composition
 *     Button(onClick = { Session.user = null }) { } // reported only with handlerWrites: true
 * }
 * ```
 *
 * Compose observes snapshot state, not a `var`: a composable that reads one shows the value it
 * read first and is never recomposed when it changes, and one that assigns one does so on every
 * recomposition, at times nobody chose. A value shared across the app belongs in a holder Compose
 * observes (`mutableStateOf`, a `StateFlow` collected as state) and reaches the composable as a
 * parameter. Not reported: a `val`, a `const`, a `lateinit var` (set once, before any
 * composition), a delegated `var` (`by mutableStateOf(...)`, `by Delegates.observable`), a `var`
 * whose type is a `State`, and an extension `var` (`var SemanticsPropertyReceiver.selected`),
 * whose setter writes into its receiver; the lazy-init idiom `cache ?: compute().also { cache = it }`
 * or `if (cache == null) cache = compute()`, which writes once and reads what it wrote; and reads
 * in a lambda that does not run during composition (an event handler, an effect), which see the
 * current value when they run. Writes in such lambdas are reported when `handlerWrites` is on.
 */
object ComposableGlobalMutableStateChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    private val STATE = ClassId(FqName("androidx.compose.runtime"), Name.identifier("State"))

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_NO_GLOBAL_MUTABLE_STATE)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (!declaration.symbol.isComposable(context.session)) return
        val body = declaration.body ?: return

        val reported = HashSet<Pair<FirPropertySymbol, Boolean>>()
        val walker = Walker(context.session, handlerWrites = config.compose.globalStateHandlerWrites) { expression, symbol, write, inComposition ->
            // One report per variable and kind of use in a function: the fix is the same for every occurrence.
            if (!reported.add(symbol to write)) return@Walker
            val name = symbol.callableId?.asSingleFqName()?.shortNameOrSpecial()?.asString()?.let { short ->
                val owner = symbol.callableId?.classId?.shortClassName?.asString()
                if (owner != null) "$owner.$short" else short
            } ?: symbol.name.asString()
            if (write) {
                val where = if (inComposition) "during composition, which runs whenever Compose decides to recompose" else "from a lambda that runs after composition (a handler, an effect)"
                reportKotrail(expression.source, KotrailDiagnostics.GLOBAL_VAR_WRITTEN_IN_COMPOSABLE, name, where)
            } else {
                reportKotrail(expression.source, KotrailDiagnostics.GLOBAL_VAR_READ_IN_COMPOSITION, name)
            }
        }
        body.accept(walker)
    }

    /**
     * Visits the body with a notion of where each lambda runs: in composition (the body, inline
     * lambdas, content slots, `remember { }`) or later (handlers, effects, anything else).
     */
    private class Walker(
        private val session: FirSession,
        private val handlerWrites: Boolean,
        private val report: (FirElement, FirPropertySymbol, write: Boolean, inComposition: Boolean) -> Unit,
    ) : FirVisitorVoid() {
        private var inComposition = true

        /** The vars a lazy-init idiom is initializing around the current element: their read and their single write are one memo. */
        private val memoized = ArrayList<FirPropertySymbol>()

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitAnonymousFunctionExpression(anonymousFunctionExpression: FirAnonymousFunctionExpression) {
            // A lambda stored in a local or returned: runs later, if at all.
            later { anonymousFunctionExpression.anonymousFunction.body?.accept(this) }
        }

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
            later { anonymousFunction.body?.accept(this) }
        }

        override fun visitAnonymousObjectExpression(anonymousObjectExpression: FirAnonymousObjectExpression) {}

        override fun visitNamedFunction(namedFunction: FirNamedFunction) {}

        override fun visitDesugaredAssignmentValueReferenceExpression(desugaredAssignmentValueReferenceExpression: FirDesugaredAssignmentValueReferenceExpression) {
            // The `x` of `x += 1` and `x++`: the assignment reports it as a write.
        }

        override fun visitElvisExpression(elvisExpression: FirElvisExpression) {
            // `cache ?: compute().also { cache = it }`: the read and the write are one lazy initialization.
            val memo = (elvisExpression.lhs as? FirPropertyAccessExpression)?.globalVar()?.takeIf { elvisExpression.rhs.assigns(it) }
            if (memo == null) {
                visitElement(elvisExpression)
                return
            }
            memoized += memo
            (elvisExpression.lhs as? FirPropertyAccessExpression)?.explicitReceiver?.accept(this)
            elvisExpression.rhs.accept(this)
            memoized.removeAt(memoized.lastIndex)
        }

        override fun visitWhenExpression(whenExpression: FirWhenExpression) {
            // `if (cache == null) cache = compute()`: the same idiom as a statement.
            whenExpression.subjectVariable?.accept(this)
            for (branch in whenExpression.branches) {
                val memo = branch.condition.nullCheckedVar()?.takeIf { branch.result.assigns(it) }
                if (memo == null) {
                    branch.condition.accept(this)
                    branch.result.accept(this)
                    continue
                }
                memoized += memo
                branch.condition.accept(this)
                branch.result.accept(this)
                memoized.removeAt(memoized.lastIndex)
            }
        }

        /** The global var of `x == null`, either way round. */
        private fun FirElement.nullCheckedVar(): FirPropertySymbol? {
            val call = this as? FirEqualityOperatorCall ?: return null
            if (call.operation != FirOperation.EQ) return null
            val arguments = call.argumentList.arguments.map { it.unwrapArgument() }
            if (arguments.size != 2) return null
            val checked = when {
                arguments[1].isNullLiteral -> arguments[0]
                arguments[0].isNullLiteral -> arguments[1]
                else -> return null
            }
            return (checked as? FirPropertyAccessExpression)?.globalVar()
        }

        /** Whether [symbol] is assigned somewhere under this element, lambdas included (`also { x = it }`). */
        private fun FirElement.assigns(symbol: FirPropertySymbol): Boolean {
            var found = false
            accept(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (!found) element.acceptChildren(this)
                }

                override fun visitVariableAssignment(variableAssignment: FirVariableAssignment) {
                    val target = variableAssignment.lValue as? FirPropertyAccessExpression
                    if (target?.calleeReference?.toResolvedCallableSymbol() == symbol) found = true else visitElement(variableAssignment)
                }
            })
            return found
        }

        override fun visitVariableAssignment(variableAssignment: FirVariableAssignment) {
            // `x += 1` and `x++` assign through a reference to the access; the desugared right-hand side reads x again, which is the same use.
            val desugared = variableAssignment.source?.kind is KtFakeSourceElementKind
            val target = when (val lValue = variableAssignment.lValue) {
                is FirPropertyAccessExpression -> lValue
                is FirDesugaredAssignmentValueReferenceExpression -> lValue.expressionRef.value as? FirPropertyAccessExpression
                else -> null
            }
            val symbol = target?.globalVar()?.takeIf { it !in memoized }
            if (symbol != null && (inComposition || handlerWrites)) {
                val at = if (desugared) target else variableAssignment
                report(at, symbol, true, inComposition)
            }
            target?.explicitReceiver?.accept(this)
            if (!desugared) variableAssignment.rValue.accept(this)
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            val symbol = propertyAccessExpression.globalVar()?.takeIf { it !in memoized }
            if (symbol != null && inComposition) report(propertyAccessExpression, symbol, false, true)
            propertyAccessExpression.explicitReceiver?.accept(this)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            functionCall.explicitReceiver?.accept(this)
            val callee = functionCall.calleeReference.toResolvedNamedFunctionSymbol()
            val mapping = functionCall.resolvedArgumentMapping
            for (argument in functionCall.argumentList.arguments) {
                val unwrapped = argument.unwrapArgument()
                if (unwrapped !is FirAnonymousFunctionExpression) {
                    argument.accept(this)
                    continue
                }
                val parameter = mapping?.get(unwrapped) ?: mapping?.get(argument)
                val runsNow = callee != null && parameter != null && functionCall.runsInComposition(callee, parameter, unwrapped)
                if (runsNow) unwrapped.anonymousFunction.body?.accept(this) else later { unwrapped.anonymousFunction.body?.accept(this) }
            }
        }

        /**
         * Whether the callee runs the lambda while composing: an inline non-composable function
         * runs it in place, a composable parameter type is a content slot, and a `remember*`
         * computes its value during composition. A suspend lambda (an effect) and a callback run later.
         */
        private fun FirFunctionCall.runsInComposition(callee: FirNamedFunctionSymbol, parameter: FirValueParameter, lambda: FirAnonymousFunctionExpression): Boolean {
            if (lambda.anonymousFunction.isSuspendLambda()) return false
            if (callee.isInline && !callee.isComposable(session) && !parameter.isNoinline) return true
            val type = parameter.returnTypeRef.coneType
            if (type.customAnnotations.any { it.toAnnotationClassId(session) == ComposeNames.COMPOSABLE }) return true
            return callee.name.asString().startsWith("remember")
        }

        override fun visitProperty(property: FirProperty) {
            // The `<unary>` temporary of `x++`: its initializer reads x, which the assignment already reports as the write.
            if (property.source?.kind is KtFakeSourceElementKind.DesugaredIncrementOrDecrement) return
            property.acceptChildren(this)
        }

        private fun FirAnonymousFunction.isSuspendLambda(): Boolean = symbol.resolvedStatus.isSuspend

        private inline fun later(block: () -> Unit) {
            val before = inComposition
            inComposition = false
            block()
            inComposition = before
        }

        /** The property, when it is a plain `var` of the top level or of an object, whose changes Compose cannot see. */
        private fun FirPropertyAccessExpression.globalVar(): FirPropertySymbol? {
            val symbol = calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol ?: return null
            if (!symbol.isVar || symbol.isLocal || symbol.isConst || symbol.isLateInit || symbol.hasDelegate) return null
            // An extension var's setter writes into its receiver (`Modifier.semantics { selected = true }`), not into shared state.
            if (symbol.receiverParameterSymbol != null) return null
            val callableId = symbol.callableId ?: return null
            val owner = callableId.classId
            if (owner != null) {
                val ownerSymbol = session.symbolProvider.getClassLikeSymbolByClassId(owner) as? FirClassSymbol<*> ?: return null
                if (ownerSymbol.classKind != ClassKind.OBJECT) return null
            }
            if (symbol.resolvedReturnType.isState()) return null
            return symbol
        }

        private fun ConeKotlinType.isState(): Boolean {
            val classId = fullyExpandedType(session).classId ?: return false
            if (classId == STATE) return true
            val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirClassSymbol<*> ?: return false
            return symbol.resolvedSuperTypes.any { it.fullyExpandedType(session).classId == STATE }
        }
    }
}

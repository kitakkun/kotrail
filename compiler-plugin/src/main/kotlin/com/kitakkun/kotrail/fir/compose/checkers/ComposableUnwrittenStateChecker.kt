@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
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
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isNullableNothing
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * Reports a local `MutableState` of a composable that only one side ever touches:
 *
 * ```kotlin
 * @Composable
 * fun Screen() {
 *     val title = remember { mutableStateOf("Home") }        // reported: nothing writes title.value
 *     var draft by remember { mutableStateOf("") }           // reported: nothing reads draft
 *     Text(title.value)
 *     Button(onClick = { draft = "sent" }) { }
 * }
 * ```
 *
 * A `MutableState` nothing writes is a value dressed up as state: `remember { "Home" }` says
 * the same with nothing to observe. A `MutableState` nothing reads still invalidates the scope
 * on every write, so the composable recomposes for a value it never shows. Only a state whose
 * every use the function can see is judged: one passed to another function, returned, or used
 * as anything but `.value` may be read or written there, and is left alone. A delegated `var` nothing assigns is a plain never-reassigned var, which
 * `preferVal` reports with its `val` fix; a delegated `val` cannot be assigned at all, so its
 * `MutableState` is reported here as never written.
 */
object ComposableUnwrittenStateChecker : FirPropertyChecker(MppCheckerKind.Common) {
    private val MUTABLE_STATE = ClassId(FqName("androidx.compose.runtime"), Name.identifier("MutableState"))
    private val VALUE = Name.identifier("value")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.COMPOSE_UNWRITTEN_STATE)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (!declaration.isLocal) return
        val session = context.session
        val delegated = declaration.delegate != null
        val stateType = if (delegated) declaration.delegate?.resolvedType else declaration.initializer?.takeIf { !declaration.isVar }?.resolvedType
        if (stateType == null || !stateType.isMutableState(session)) return

        val enclosing = context.containingDeclarations.lastOrNull { it is FirNamedFunctionSymbol } as? FirNamedFunctionSymbol ?: return
        if (!enclosing.isComposable(session)) return
        val body = enclosing.fir.body ?: return

        val usages = UsageCollector(declaration.symbol, delegated)
        body.accept(usages)
        if (usages.escapes) return
        val name = declaration.name.asString()
        when {
            usages.reads > 0 && usages.writes > 0 -> return
            usages.writes > 0 -> reportKotrail(source, KotrailDiagnostics.STATE_NEVER_READ, name)
            delegated && declaration.isVar -> return
            else -> reportKotrail(source, KotrailDiagnostics.STATE_NEVER_WRITTEN, name)
        }
    }

    private fun ConeKotlinType.isMutableState(session: FirSession): Boolean {
        val expanded = fullyExpandedType(session)
        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(MUTABLE_STATE) ?: return false
        val superType = MUTABLE_STATE.constructClassLikeType(Array(symbol.typeParameterSymbols.size) { ConeStarProjection }, isMarkedNullable = true)
        return !expanded.isNullableNothing && AbstractTypeChecker.isSubtypeOf(session.typeContext, expanded, superType)
    }

    /**
     * Counts the reads and writes of the local's state, lambdas included: `x` and `x = ...` for a
     * delegated local, `s.value` and `s.value = ...` for a plain one. Any other use of a plain
     * local hands the `MutableState` to code this function cannot see, and is an escape. A
     * delegated local cannot escape: its value is what every mention of it reads or writes.
     */
    private class UsageCollector(private val target: FirPropertySymbol, private val delegated: Boolean) : FirVisitorVoid() {
        var reads: Int = 0
            private set
        var writes: Int = 0
            private set
        var escapes: Boolean = false
            private set

        override fun visitElement(element: FirElement) {
            if (!escapes) element.acceptChildren(this)
        }

        override fun visitProperty(property: FirProperty) {
            // A delegated local's synthesized accessors refer to the local itself.
            if (property.symbol == target) return
            // The `<unary>` temporary of `x++`: its initializer reads x, and the assignment is the
            // use.
            if (property.source?.kind is KtFakeSourceElementKind.DesugaredIncrementOrDecrement) return
            visitElement(property)
        }

        // The `x` of `x += 1` and `x++`: the assignment counts it as a write.
        override fun visitDesugaredAssignmentValueReferenceExpression(desugaredAssignmentValueReferenceExpression: FirDesugaredAssignmentValueReferenceExpression) {
        }

        override fun visitVariableAssignment(variableAssignment: FirVariableAssignment) {
            val lValue = when (val value = variableAssignment.lValue) {
                is FirDesugaredAssignmentValueReferenceExpression -> value.expressionRef.value
                else -> value
            } as? FirPropertyAccessExpression
            if (lValue != null && lValue.isStateAccess()) {
                writes++
                variableAssignment.rValue.accept(this)
                return
            }
            visitElement(variableAssignment)
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            if (propertyAccessExpression.isStateAccess()) {
                reads++
                return
            }
            if (propertyAccessExpression.refersToTarget()) {
                escapes = true
                return
            }
            propertyAccessExpression.acceptChildren(this)
        }

        /** Whether this is the state's value: `x` for a delegated local, `s.value` for a plain one. */
        private fun FirPropertyAccessExpression.isStateAccess(): Boolean {
            if (delegated) return refersToTarget()
            val receiver = explicitReceiver as? FirPropertyAccessExpression
            return calleeReference.name == VALUE && receiver.refersToTarget()
        }

        private fun FirPropertyAccessExpression?.refersToTarget(): Boolean =
            this != null && calleeReference.toResolvedCallableSymbol() == target
    }
}

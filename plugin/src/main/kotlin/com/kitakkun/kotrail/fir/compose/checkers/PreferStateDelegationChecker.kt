@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.insets.WindowInsetsNames
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.references.toResolvedPropertySymbol
import org.jetbrains.kotlin.fir.resolve.isSubclassOf
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Asks for property delegation on local Compose state holders:
 *
 * ```kotlin
 * val count = remember { mutableStateOf(0) }   // rejected when only count.value is used
 * var count by remember { mutableStateOf(0) }  // what the rule asks for
 * ```
 *
 * A local `val` inside a composable whose type is a `State` and which is used exclusively
 * through `.value` is reported. If the `State` object itself escapes (passed as an argument,
 * returned, captured as a receiver of anything but `.value`), delegation would change the
 * program, so nothing is reported.
 */
object PreferStateDelegationChecker : FirPropertyChecker(MppCheckerKind.Common) {
    private val STATE = ClassId(FqName("androidx.compose.runtime"), Name.identifier("State"))
    private val MUTABLE_STATE = ClassId(FqName("androidx.compose.runtime"), Name.identifier("MutableState"))
    private val VALUE = Name.identifier("value")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.COMPOSE_STATE_DELEGATION)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (!declaration.isLocal || declaration.delegate != null || declaration.isVar) return
        if (declaration.initializer == null) return

        val session = context.session
        val type = declaration.returnTypeRef.coneType
        if (!type.isSubclassOf(STATE, session)) return

        val enclosing = context.containingDeclarations.lastOrNull { it is FirNamedFunctionSymbol } as? FirNamedFunctionSymbol ?: return
        if (!enclosing.hasAnnotation(WindowInsetsNames.COMPOSABLE, session)) return
        val body = enclosing.fir.body ?: return

        val usages = UsageCollector(declaration.symbol)
        body.accept(usages)
        if (usages.escapes || usages.valueAccesses == 0) return

        val keyword = if (type.isSubclassOf(MUTABLE_STATE, session)) "var" else "val"
        val severity = context.session.kotrailConfig.severity(KotrailRule.COMPOSE_STATE_DELEGATION)
        reporter.reportOn(source, KotrailDiagnostics.PREFER_STATE_DELEGATION.at(severity), declaration.name.asString(), keyword)
    }

    private fun ConeKotlinType.isSubclassOf(classId: ClassId, session: FirSession): Boolean {
        val symbol = toRegularClassSymbol(session) ?: return false
        return symbol.isSubclassOf(classId.toLookupTag(), session, isStrict = false, lookupInterfaces = true)
    }

    /** Counts `.value` accesses on the local and detects any other use of it. */
    private class UsageCollector(private val target: FirPropertySymbol) : FirVisitorVoid() {
        var valueAccesses: Int = 0
            private set
        var escapes: Boolean = false
            private set

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            val receiver = propertyAccessExpression.explicitReceiver as? FirPropertyAccessExpression
            if (propertyAccessExpression.calleeReference.name == VALUE && receiver.refersToTarget()) {
                valueAccesses++
                // The receiver is the local itself; do not descend into it or it would count as an escape.
                return
            }
            if (propertyAccessExpression.refersToTarget()) {
                escapes = true
                return
            }
            propertyAccessExpression.acceptChildren(this)
        }

        private fun FirPropertyAccessExpression?.refersToTarget(): Boolean =
            this != null && calleeReference.toResolvedPropertySymbol() == target
    }
}

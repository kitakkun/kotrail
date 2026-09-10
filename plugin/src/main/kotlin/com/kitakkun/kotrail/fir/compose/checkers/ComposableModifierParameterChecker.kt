package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.references.toResolvedValueParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Enforces the `modifier: Modifier = Modifier` convention on UI composables that accept a
 * `Modifier`:
 *
 * ```kotlin
 * @Composable
 * fun Card(title: String, modifier: Modifier = Modifier) {   // the shape this rule asks for
 *     Column(modifier) { Text(title) }                       // applied once, to the root
 * }
 * ```
 *
 * Reported deviations, joined into one diagnostic per function: several `Modifier` parameters;
 * a parameter not named `modifier`; a missing default or a default other than the `Modifier`
 * companion; an optional parameter placed before `modifier`; and a `modifier` that the body
 * never uses or applies to more than one element. The rule stays quiet for private and local
 * composables, value-returning composables, overrides and `expect` declarations, and composables
 * without any `Modifier` parameter (it does not demand adding one).
 */
object ComposableModifierParameterChecker : FirSimpleFunctionChecker(MppCheckerKind.Common) {
    private val MODIFIER_NAME = Name.identifier("modifier")
    private val MODIFIER_COMPANION: ClassId = ComposeNames.MODIFIER.createNestedClassId(Name.identifier("Companion"))

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.COMPOSE_MODIFIER_PARAMETER)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.isOverride || declaration.isExpect) return
        val visibility = declaration.visibility
        if (visibility != Visibilities.Public && visibility != Visibilities.Internal) return
        if (!declaration.symbol.isComposable(context.session)) return
        if (!declaration.returnTypeRef.coneType.isUnit) return

        val parameters = declaration.valueParameters
        val modifierParameters = parameters.filter { it.returnTypeRef.coneType.classId == ComposeNames.MODIFIER }
        val modifier = modifierParameters.firstOrNull() ?: return

        val findings = mutableListOf<String>()
        if (modifierParameters.size > 1) {
            findings += "only one Modifier parameter is allowed"
        }
        if (modifier.name != MODIFIER_NAME) {
            findings += "the Modifier parameter must be named 'modifier'"
        }
        if (!modifier.defaultValue.isModifierCompanion()) {
            findings += "'modifier' must default to 'Modifier'"
        }
        val firstOptional = parameters.firstOrNull { it.defaultValue != null }
        if (firstOptional != null && firstOptional !== modifier) {
            findings += "'modifier' must be the first optional parameter"
        }
        val body = declaration.body
        if (body != null) {
            val counter = UseCounter(modifier.symbol)
            body.accept(counter)
            when {
                counter.uses == 0 -> findings += "'modifier' is never used"
                counter.uses > 1 -> findings += "'modifier' is passed more than once (apply it to a single root element)"
            }
        }
        if (findings.isEmpty()) return

        reportKotrail(source, KotrailDiagnostics.COMPOSABLE_MODIFIER_PARAMETER, findings.joinToString("; "))
    }

    /** True for a default value written as `Modifier` (resolved to the companion) or `Modifier.Companion`. */
    private fun FirExpression?.isModifierCompanion(): Boolean {
        val qualifier = this as? FirResolvedQualifier ?: return false
        val classId = qualifier.classId ?: return false
        return classId == MODIFIER_COMPANION || (classId == ComposeNames.MODIFIER && qualifier.resolvedToCompanionObject)
    }

    /** Counts reads of one value parameter anywhere in the body, nested lambdas included. */
    private class UseCounter(private val parameter: FirValueParameterSymbol) : FirVisitorVoid() {
        var uses = 0

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            if (propertyAccessExpression.calleeReference.toResolvedValueParameterSymbol() == parameter) {
                uses++
            }
            propertyAccessExpression.acceptChildren(this)
        }
    }
}

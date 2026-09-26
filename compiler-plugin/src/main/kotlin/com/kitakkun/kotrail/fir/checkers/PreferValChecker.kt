@file:OptIn(DirectDeclarationsAccess::class, SymbolInternals::class)

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.fix.FixBuilder
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertySetter
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.utils.isActual
import org.jetbrains.kotlin.fir.declarations.utils.isConst
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isLateInit
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousInitializerSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Reports a `var` that is never reassigned after its initializer:
 *
 * ```kotlin
 * var total = items.sumOf { it.price }     // reported: nothing assigns to total again
 * println(total)
 * ```
 *
 * `var` tells the reader to watch for the value changing, and every later line has to be read
 * with that in mind. When nothing assigns to it, `val` says so at the declaration. Locals are
 * checked against the body that declares them (lambdas inside included), private members
 * against their class, and private top-level properties against their file, which is exactly
 * the scope from which they can be assigned. A `var` without an initializer, with a custom
 * accessor, delegated, `lateinit`, `const`, `override`, `open`, `expect`, or `actual` is left
 * alone, as is a component of a destructuring declaration. The fix replaces the keyword.
 */
object PreferValChecker : FirPropertyChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.PREFER_VAL)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (!declaration.isVar) return
        if (declaration.initializer == null || declaration.initializer?.source?.kind is KtFakeSourceElementKind) return
        if (declaration.delegate != null || declaration.isLateInit || declaration.isConst) return
        if (declaration.isOverride || declaration.isExpect || declaration.isActual) return
        if (declaration.setter != null && declaration.setter !is FirDefaultPropertySetter) return
        if (declaration.getter != null && declaration.getter?.source?.kind !is KtFakeSourceElementKind) return
        if (!declaration.isLocal) {
            if (declaration.visibility != Visibilities.Private) return
            if (declaration.modality != Modality.FINAL) return
        }

        val scope = context.assignmentScope(declaration) ?: return
        val finder = AssignmentFinder(declaration.symbol)
        scope.accept(finder)
        if (finder.found) return

        val fix = FixBuilder.over(source)?.replaceFirst(Regex("(?<![\\w.])var(?=\\s)"), "val")
        reportKotrail(source, KotrailDiagnostics.PREFER_VAL, declaration.name.asString(), listOfNotNull(fix))
    }

    /** Everything that could assign to the property: the declaring body, the class, or the file. */
    private fun CheckerContext.assignmentScope(declaration: FirProperty): FirElement? {
        if (declaration.isLocal) {
            return containingDeclarations.lastOrNull {
                it is FirFunctionSymbol<*> || it is FirAnonymousInitializerSymbol || (it is FirPropertySymbol && it != declaration.symbol)
            }?.fir
        }
        val owner = containingDeclarations.lastOrNull { it is FirClassSymbol<*> } as? FirClassSymbol<*>
        return owner?.fir ?: containingFileSymbol?.fir
    }

    private class AssignmentFinder(private val target: FirPropertySymbol) : FirVisitorVoid() {
        var found = false

        override fun visitElement(element: FirElement) {
            if (found) return
            element.acceptChildren(this)
        }

        override fun visitVariableAssignment(variableAssignment: FirVariableAssignment) {
            // `x += 1` and `x++` assign through a reference to the original access.
            val lValue = when (val value = variableAssignment.lValue) {
                is FirDesugaredAssignmentValueReferenceExpression -> value.expressionRef.value
                else -> value
            }
            val assigned = (lValue as? FirQualifiedAccessExpression)?.calleeReference?.toResolvedCallableSymbol()
            if (assigned == target) {
                found = true
                return
            }
            variableAssignment.acceptChildren(this)
        }
    }
}

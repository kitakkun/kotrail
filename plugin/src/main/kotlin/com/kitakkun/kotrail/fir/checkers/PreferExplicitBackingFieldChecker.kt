package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.declaredProperties
import org.jetbrains.kotlin.fir.declarations.utils.hasExplicitBackingField
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.references.toResolvedPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * Reports the pre-Kotlin-2.4 "backing property" idiom:
 *
 * ```kotlin
 * private val _items = MutableStateFlow<List<Item>>(emptyList())
 * val items: StateFlow<List<Item>> = _items.asStateFlow()
 * ```
 *
 * and asks for an explicit backing field instead:
 *
 * ```kotlin
 * val items: StateFlow<List<Item>>
 *     field = MutableStateFlow(emptyList())
 * ```
 *
 * A pair is reported when, inside the same class, a `val` named `foo` references a
 * property named `_foo` whose visibility is strictly narrower than `foo`'s, and `foo`
 * can legally carry an explicit backing field (final, not `expect`, not an extension).
 *
 * The same pair with a `var` behind a getter that returns it as is,
 *
 * ```kotlin
 * private var _count = 0
 * val count: Int get() = _count
 * ```
 *
 * needs no backing field at all: `var count = 0` with `private set` says the same thing on
 * every Kotlin version. That case is the prefer-private-setter rule's, reported here through
 * the same pair detection so that one pair gets one diagnostic.
 */
object PreferExplicitBackingFieldChecker : FirPropertyChecker(MppCheckerKind.Common) {
    private const val BACKING_PREFIX = "_"

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        val config = context.session.kotrailConfig
        val backingFieldRule = config.isEnabled(KotrailRule.PREFER_EXPLICIT_BACKING_FIELD)
        val privateSetterRule = config.isEnabled(KotrailRule.PREFER_PRIVATE_SETTER)
        if (!backingFieldRule && !privateSetterRule) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (!declaration.origin.fromSource) return
        if (!canCarryExplicitBackingField(declaration)) return

        val name = declaration.name.asString()
        if (name.startsWith(BACKING_PREFIX)) return

        val containingClass = context.containingDeclarations.lastOrNull() as? FirClassSymbol<*> ?: return
        val backingName = BACKING_PREFIX + name
        val backing = containingClass.declaredProperties(context.session)
            .firstOrNull { it.name.asString() == backingName }
            ?: return

        val comparison = Visibilities.compare(backing.visibility, declaration.visibility)
        if (comparison == null || comparison >= 0) return

        if (!references(declaration, backing)) return

        if (backing.isVar && returnsAsIs(declaration, backing)) {
            if (privateSetterRule) reportKotrail(source, KotrailDiagnostics.PREFER_PRIVATE_SETTER, backingName)
            return
        }
        if (!backingFieldRule) return
        // The rewrite the rule asks for only compiles where the language feature is on: by default
        // from Kotlin 2.4.0, behind -Xexplicit-backing-fields before that. Elsewhere the rule would
        // demand something the compiler rejects, so it stays quiet.
        if (!context.session.languageVersionSettings.supportsFeature(LanguageFeature.ExplicitBackingFields)) return
        reportKotrail(source, KotrailDiagnostics.PREFER_EXPLICIT_BACKING_FIELD, backingName)
    }

    /**
     * Whether [property] is a getter that returns [backing] unchanged, of the same type: the
     * shape `private set` replaces. A conversion (`_x.toList()`), a wider exposed type
     * (`MutableStateFlow` behind `StateFlow`), or an initializer copy is something else.
     */
    private fun returnsAsIs(property: FirProperty, backing: FirPropertySymbol): Boolean {
        if (property.initializer != null || property.delegate != null) return false
        val getter = property.getter ?: return false
        val body = getter.body ?: return false
        val statement = body.statements.singleOrNull() ?: return false
        val returned = (statement as? FirReturnExpression)?.result ?: statement as? FirExpression ?: return false
        val access = returned as? FirPropertyAccessExpression ?: return false
        if (access.calleeReference.toResolvedPropertySymbol() != backing) return false
        return AbstractTypeChecker.equalTypes(
            property.moduleData.session.typeContext,
            property.returnTypeRef.coneType,
            backing.resolvedReturnType,
        )
    }

    private fun canCarryExplicitBackingField(property: FirProperty): Boolean {
        if (property.isLocal) return false
        if (!property.isVal) return false
        if (property.isExpect) return false
        if (property.receiverParameter != null) return false
        if (property.modality != Modality.FINAL) return false
        if (property.symbol.hasExplicitBackingField) return false
        return true
    }

    /** Whether the initializer, delegate, or getter of [property] reads [target]. */
    private fun references(property: FirProperty, target: FirPropertySymbol): Boolean {
        val finder = PropertyReferenceFinder(target)
        property.initializer?.accept(finder)
        property.delegate?.accept(finder)
        property.getter?.accept(finder)
        return finder.found
    }

    private class PropertyReferenceFinder(private val target: FirPropertySymbol) : FirVisitorVoid() {
        var found: Boolean = false
            private set

        override fun visitElement(element: FirElement) {
            if (found) return
            element.acceptChildren(this)
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            if (propertyAccessExpression.calleeReference.toResolvedPropertySymbol() == target) {
                found = true
                return
            }
            visitElement(propertyAccessExpression)
        }
    }
}

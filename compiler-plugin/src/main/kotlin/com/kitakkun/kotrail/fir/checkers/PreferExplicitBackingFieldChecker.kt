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
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.declaredProperties
import org.jetbrains.kotlin.fir.declarations.utils.hasExplicitBackingField
import org.jetbrains.kotlin.fir.declarations.utils.isEnumClass
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isFinal
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.unwrapSmartcastExpression
import org.jetbrains.kotlin.fir.references.toResolvedPropertySymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.types.AbstractTypeChecker
import java.util.Collections
import java.util.IdentityHashMap

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
 * A pair is reported when, inside the same class, a `val` `foo` that can legally carry an
 * explicit backing field (effectively final, not `expect`, not an extension; an `override val`
 * in a final class counts, since nothing can override it further) reads a backing property
 * declared in the same class with strictly narrower visibility. A property named `_foo` is
 * taken as the backing property on its name. A private property of any other name is taken on
 * its use: `foo` is that property or one argument-free call on it, and nothing else in the
 * class reads it except to call on it or assign to it.
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
        val containingClass = context.containingDeclarations.lastOrNull() as? FirClassSymbol<*> ?: return
        if (!canCarryExplicitBackingField(declaration, containingClass)) return

        val name = declaration.name.asString()
        if (name.startsWith(BACKING_PREFIX)) return

        val backing = backingProperty(declaration, containingClass, context.session) ?: return
        val backingName = backing.name.asString()

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
     * The property of [containingClass] that [property] exposes, if any: `_foo` behind `foo` when
     * it is narrower and `foo` reads it, or else a property of another name found by its use.
     */
    private fun backingProperty(
        property: FirProperty,
        containingClass: FirClassSymbol<*>,
        session: FirSession,
    ): FirPropertySymbol? {
        val declared = containingClass.declaredProperties(session)
        val prefixedName = BACKING_PREFIX + property.name.asString()
        val prefixed = declared.firstOrNull { it.name.asString() == prefixedName }
        if (prefixed != null && isNarrower(prefixed, property) && references(property, prefixed)) return prefixed
        return backingByUse(property, containingClass, declared)
    }

    /**
     * The backing property behind [property] when its name does not say so. [property]'s value
     * must be the property itself or one argument-free call on it (`state`,
     * `state.asStateFlow()`), so that a value derived from it (`state.map { ... }`,
     * `combine(a, b) { ... }`) is not mistaken for it. The property must also be:
     * - private, so the class holds every read of it;
     * - of a subtype of [property]'s type, which an explicit backing field has to be
     *   (`FirPropertyFieldTypeChecker` in the compiler reports `INCONSISTENT_BACKING_FIELD_TYPE`
     *   otherwise);
     * - read nowhere else in the class except to call on it or assign to it ([handsOut]).
     */
    private fun backingByUse(
        property: FirProperty,
        containingClass: FirClassSymbol<*>,
        declared: List<FirPropertySymbol>,
    ): FirPropertySymbol? {
        val candidate = exposedProperty(property) ?: return null
        if (candidate == property.symbol || candidate !in declared) return null
        if (!Visibilities.isPrivate(candidate.visibility) || !isNarrower(candidate, property)) return null
        val isSubtype = AbstractTypeChecker.isSubtypeOf(
            property.moduleData.session.typeContext,
            candidate.resolvedReturnType,
            property.returnTypeRef.coneType,
        )
        if (!isSubtype) return null
        if (handsOut(containingClass, property, candidate)) return null
        return candidate
    }

    private fun isNarrower(backing: FirPropertySymbol, property: FirProperty): Boolean {
        val comparison = Visibilities.compare(backing.visibility, property.visibility)
        return comparison != null && comparison < 0
    }

    /** The property that [property]'s value reads, as is or through one argument-free call. */
    private fun exposedProperty(property: FirProperty): FirPropertySymbol? {
        if (property.delegate != null) return null
        val value = property.initializer ?: returnedExpression(property) ?: return null
        val unwrapped = value.unwrapSmartcastExpression()
        val accessed = if (unwrapped is FirFunctionCall) {
            if (unwrapped.argumentList.arguments.isNotEmpty()) return null
            unwrapped.explicitReceiver?.unwrapSmartcastExpression() ?: return null
        } else {
            unwrapped
        }
        return (accessed as? FirPropertyAccessExpression)?.calleeReference?.toResolvedPropertySymbol()
    }

    /**
     * Whether [containingClass], outside [exposing], reads [backing] for anything but a call on it
     * or an assignment to it. `backing.value = 1`, `backing.update { }`, `backing.add(x)`,
     * `backing = x` and `backing++` keep the object inside the class; `return backing`,
     * `consume(backing)` and `val alias = backing` hand it out.
     */
    @OptIn(SymbolInternals::class)
    private fun handsOut(containingClass: FirClassSymbol<*>, exposing: FirProperty, backing: FirPropertySymbol): Boolean {
        val finder = BackingUseFinder(backing, exposing.symbol)
        containingClass.fir.accept(finder)
        return finder.reads.any { it !in finder.containedUses }
    }

    /** The expression [property]'s getter returns, when its body is that one expression. */
    private fun returnedExpression(property: FirProperty): FirExpression? {
        val body = property.getter?.body ?: return null
        val statement = body.statements.singleOrNull() ?: return null
        return (statement as? FirReturnExpression)?.result ?: statement as? FirExpression
    }

    /**
     * Whether [property] is a getter that returns [backing] unchanged, of the same type: the
     * shape `private set` replaces. A conversion (`_x.toList()`), a wider exposed type
     * (`MutableStateFlow` behind `StateFlow`), or an initializer copy is something else.
     */
    private fun returnsAsIs(property: FirProperty, backing: FirPropertySymbol): Boolean {
        if (property.initializer != null || property.delegate != null) return false
        val access = returnedExpression(property) as? FirPropertyAccessExpression ?: return false
        if (access.calleeReference.toResolvedPropertySymbol() != backing) return false
        return AbstractTypeChecker.equalTypes(
            property.moduleData.session.typeContext,
            property.returnTypeRef.coneType,
            backing.resolvedReturnType,
        )
    }

    /**
     * Whether the compiler accepts `field = ...` on [property], following
     * `FirExplicitBackingFieldForbiddenChecker` (Kotlin 2.4.10,
     * compiler/fir/checkers/src/org/jetbrains/kotlin/fir/analysis/checkers/declaration/FirExplicitBackingFieldForbiddenChecker.kt:30-44):
     * not in an interface, not abstract, not an extension, not `expect`, and effectively final. Effectively
     * final (`isEffectivelyFinal`, declarationUtils.kt:53-63 in the same directory) is final by modality, or
     * a member of a final class that is not an enum class. So an `override val` without `final`, which
     * resolves to open modality, still takes the field inside a final class or an object.
     */
    private fun canCarryExplicitBackingField(property: FirProperty, containingClass: FirClassSymbol<*>): Boolean {
        if (property.isLocal) return false
        if (!property.isVal) return false
        if (property.isExpect) return false
        if (property.receiverParameter != null) return false
        if (property.symbol.hasExplicitBackingField) return false
        if (containingClass.isInterface) return false
        if (property.modality == Modality.ABSTRACT) return false
        if (property.modality == Modality.FINAL) return true
        return containingClass.isFinal && !containingClass.isEnumClass
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

    /**
     * Collects every access to [target] outside the property [exposing], and every expression used
     * as a receiver or as an assignment target. An access that is not among the latter hands the
     * object out.
     */
    private class BackingUseFinder(
        private val target: FirPropertySymbol,
        private val exposing: FirPropertySymbol,
    ) : FirVisitorVoid() {
        val reads: MutableList<FirExpression> = mutableListOf()

        // By identity: a use clears that very access, not every access to the same property.
        val containedUses: MutableSet<FirExpression> = Collections.newSetFromMap(IdentityHashMap())

        override fun visitElement(element: FirElement) {
            if (element is FirProperty && element.symbol == exposing) return
            when (element) {
                is FirQualifiedAccessExpression -> {
                    if (element.calleeReference.toResolvedPropertySymbol() == target) reads += element
                    element.explicitReceiver?.let { containedUses += it.unwrapSmartcastExpression() }
                }
                is FirSafeCallExpression -> containedUses += element.receiver.unwrapSmartcastExpression()
                is FirVariableAssignment -> {
                    // `x += 1` and `x++` assign through a reference to the access they read.
                    val lValue = element.lValue
                    containedUses += (lValue as? FirDesugaredAssignmentValueReferenceExpression)?.expressionRef?.value ?: lValue
                }
            }
            element.acceptChildren(this)
        }
    }
}

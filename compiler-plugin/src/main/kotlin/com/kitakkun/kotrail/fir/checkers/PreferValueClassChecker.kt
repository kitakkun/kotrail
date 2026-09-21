@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.fromPrimaryConstructor
import org.jetbrains.kotlin.fir.declarations.utils.hasBackingField
import org.jetbrains.kotlin.fir.declarations.utils.isData
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isInner
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.impl.FirImplicitAnyTypeRef
import org.jetbrains.kotlin.fir.types.isNothing
import org.jetbrains.kotlin.fir.types.isUnit

/**
 * Asks for a `@JvmInline value class` instead of a `data class` that wraps a single value:
 *
 * ```kotlin
 * data class UserId(val raw: Long)          // -> @JvmInline value class UserId(val raw: Long)
 * ```
 *
 * A one-property data class exists to give a primitive a type; a value class does the same
 * without allocating a wrapper object, and keeps the `equals`/`hashCode`/`toString` a data
 * class would generate. The rule reports only when the rewrite is legal and behavior
 * preserving: the class is a final, top-level or nested (not `inner`, not local) data class
 * whose primary constructor declares exactly one `val` property, with no other properties that
 * need a backing field, no secondary constructors, no supertypes at all, no type parameters,
 * and no annotations at all. A supertype, interface included, means the value travels as that
 * type (a sealed action handed to `onAction(action)`, an `Identifier` in a `List<Identifier>`),
 * and a value class held as a supertype is boxed, so the rewrite would save nothing and cost
 * `copy()` and a second property. Annotations such as `@Serializable` or `@Parcelize` usually
 * depend on the class being a data class, so any annotation keeps the rule quiet. `expect`
 * declarations and classes whose property type is `Unit` or `Nothing` (illegal in a value
 * class) are also left alone.
 */
object PreferValueClassChecker : FirRegularClassChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.PREFER_VALUE_CLASS)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (!declaration.isData || declaration.classKind != ClassKind.CLASS) return
        if (declaration.isInner || declaration.isLocal || declaration.isExpect) return
        if (declaration.modality != Modality.FINAL) return
        if (declaration.annotations.isNotEmpty()) return
        if (declaration.typeParameters.isNotEmpty()) return
        if (declaration.contextParameters.isNotEmpty()) return

        // A supertype, interface included, means the value is handled as that type and boxed there.
        if (declaration.superTypeRefs.any { it !is FirImplicitAnyTypeRef }) return

        val constructors = declaration.declarations.filterIsInstance<FirConstructor>()
        val primaryConstructor = constructors.singleOrNull() ?: return
        if (!primaryConstructor.isPrimary) return
        val parameter = primaryConstructor.valueParameters.singleOrNull() ?: return
        if (parameter.isVararg) return
        val parameterType = parameter.returnTypeRef.coneType.fullyExpandedType()
        if (parameterType.isUnit || parameterType.isNothing) return

        val properties = declaration.declarations.filterIsInstance<FirProperty>()
        val constructorProperty = properties.singleOrNull { it.fromPrimaryConstructor == true } ?: return
        if (constructorProperty.isVar || constructorProperty.name != parameter.name) return
        val hasOtherStoredProperty = properties.any {
            it !== constructorProperty && (it.delegate != null || it.hasBackingField)
        }
        if (hasOtherStoredProperty) return

        reportKotrail(source, KotrailDiagnostics.PREFER_VALUE_CLASS, declaration.name.asString())
    }
}

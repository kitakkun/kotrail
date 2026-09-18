package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.utils.effectiveVisibility
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjectionIn
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjectionOut
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.abbreviatedTypeOrSelf
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * Reports a public or protected function or property whose signature exposes a mutable
 * collection type:
 *
 * ```kotlin
 * fun tags(): MutableList<String>            // -> List<String>
 * val cache: HashMap<String, Int>            // -> Map<String, Int>
 * fun register(names: MutableSet<String>)    // -> Set<String>
 * ```
 *
 * A mutable type in a public signature lets every caller change state the owner did not mean to
 * share; the read-only interface says what the API promises. Only the top-level type of the return
 * type and of each value parameter is checked (`List<MutableList<Int>>` is not reported), and the
 * concrete JVM classes (`ArrayList`, `HashMap`, ...) count as mutable, whether written through the
 * `kotlin.collections` alias or the `java.util` class. Private, internal, and local declarations,
 * `override`s (the supertype fixes the signature), `expect` declarations, constructors, and
 * property accessors are left alone.
 */
object MutableCollectionInPublicApiChecker : FirCallableDeclarationChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirCallableDeclaration) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.NO_MUTABLE_COLLECTION_IN_PUBLIC_API)) return
        if (declaration !is FirNamedFunction && declaration !is FirProperty) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.isLocal || declaration.isOverride || declaration.isExpect) return
        if (!declaration.effectiveVisibility.publicApi) return

        checkTypeRef(declaration.returnTypeRef, source)
        if (declaration is FirNamedFunction) {
            for (parameter in declaration.valueParameters) {
                checkTypeRef(parameter.returnTypeRef, parameter.source ?: source)
            }
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkTypeRef(typeRef: FirTypeRef, fallback: KtSourceElement) {
        val resolved = typeRef as? FirResolvedTypeRef ?: return
        val type = resolved.coneType
        val expanded = type.fullyExpandedType()
        val written = type.abbreviatedTypeOrSelf.classId
        val replacement = READ_ONLY_COUNTERPART[expanded.classId]
            ?: READ_ONLY_COUNTERPART[written]
            ?: return
        val shownName = (written ?: expanded.classId ?: return).shortClassName.asString()
        val typeArguments = renderTypeArguments(type)
        val nullability = if (type.isMarkedNullable) "?" else ""
        val mutableText = "$shownName$typeArguments$nullability"
        val readOnlyText = "${replacement.asString()}$typeArguments$nullability"

        // Implicit types (`fun f() = mutableListOf(1)`) have no real type-ref source; point at the declaration.
        val typeSource = resolved.source?.takeIf { it.kind !is KtFakeSourceElementKind } ?: fallback
        reportKotrail(
            typeSource,
            KotrailDiagnostics.MUTABLE_COLLECTION_IN_PUBLIC_API,
            "$mutableText; expose $readOnlyText instead",
        )
    }

    /** `<K, V>` as written by the user (short names), or an empty string for a raw type. */
    private fun renderTypeArguments(type: ConeKotlinType): String {
        if (type.typeArguments.isEmpty()) return ""
        return type.typeArguments.joinToString(prefix = "<", postfix = ">") { projection ->
            when (projection) {
                is ConeKotlinTypeProjectionIn -> "in ${projection.type.renderReadable()}"
                is ConeKotlinTypeProjectionOut -> "out ${projection.type.renderReadable()}"
                is ConeKotlinTypeProjection -> projection.type.renderReadable()
                else -> "*"
            }
        }
    }

    private val JAVA_UTIL = FqName("java.util")

    private fun javaUtil(name: String): ClassId = ClassId(JAVA_UTIL, Name.identifier(name))
    private fun kotlinCollections(name: String): ClassId = ClassId(StandardClassIds.BASE_COLLECTIONS_PACKAGE, Name.identifier(name))

    /** Mutable type -> the read-only interface's short name to suggest. */
    private val READ_ONLY_COUNTERPART: Map<ClassId, Name> = buildMap {
        put(StandardClassIds.MutableList, StandardClassIds.List.shortClassName)
        put(StandardClassIds.MutableSet, StandardClassIds.Set.shortClassName)
        put(StandardClassIds.MutableMap, StandardClassIds.Map.shortClassName)
        put(StandardClassIds.MutableCollection, StandardClassIds.Collection.shortClassName)
        put(StandardClassIds.MutableIterable, StandardClassIds.Iterable.shortClassName)
        put(StandardClassIds.MutableIterator, StandardClassIds.Iterator.shortClassName)
        for (name in listOf("ArrayList")) {
            put(kotlinCollections(name), StandardClassIds.List.shortClassName)
            put(javaUtil(name), StandardClassIds.List.shortClassName)
        }
        for (name in listOf("HashSet", "LinkedHashSet")) {
            put(kotlinCollections(name), StandardClassIds.Set.shortClassName)
            put(javaUtil(name), StandardClassIds.Set.shortClassName)
        }
        for (name in listOf("HashMap", "LinkedHashMap")) {
            put(kotlinCollections(name), StandardClassIds.Map.shortClassName)
            put(javaUtil(name), StandardClassIds.Map.shortClassName)
        }
    }
}

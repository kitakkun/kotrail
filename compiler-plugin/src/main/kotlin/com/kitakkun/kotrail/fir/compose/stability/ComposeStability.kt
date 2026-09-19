@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.compose.stability

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassLikeSymbol
import org.jetbrains.kotlin.fir.declarations.utils.hasBackingField
import org.jetbrains.kotlin.fir.declarations.utils.isDelegatedProperty
import org.jetbrains.kotlin.fir.declarations.utils.isEnumClass
import org.jetbrains.kotlin.fir.declarations.utils.isInlineOrValue
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeErrorType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isPrimitiveOrNullablePrimitive
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * What the Compose compiler concludes about a type, in its own terms: certainly stable or
 * unstable, unknown (an interface, whose implementations decide), or depending on a type
 * parameter. A class analyzed across a file or module boundary is `Runtime` in the Compose
 * compiler, resolved through the class's `$stable` field when the app runs; here the class's own
 * inference is substituted for that field, since that is the value the field holds.
 */
sealed class Stability {
    /** Certainly stable, or certainly unstable for [reason]. */
    class Certain(val stable: Boolean, val reason: String? = null) : Stability()

    /** An interface: nothing is known until an implementation is seen, and Compose treats it as unstable at runtime. */
    class Unknown(val classSymbol: FirRegularClassSymbol) : Stability()

    /** As stable as the type argument bound to [symbol] turns out to be. */
    class Parameter(val symbol: FirTypeParameterSymbol) : Stability()

    class Combined(val elements: List<Stability>) : Stability()

    operator fun plus(other: Stability): Stability = when {
        other is Certain -> if (other.stable) this else other
        this is Certain -> if (stable) other else this
        else -> Combined(listOf(this, other))
    }

    fun knownStable(): Boolean = when (this) {
        is Certain -> stable
        is Unknown, is Parameter -> false
        is Combined -> elements.all { it.knownStable() }
    }

    fun knownUnstable(): Boolean = when (this) {
        is Certain -> !stable
        is Unknown, is Parameter -> false
        is Combined -> elements.any { it.knownUnstable() }
    }

    /**
     * Why Compose treats a value of this type as unstable when the app runs, or `null` when it
     * does not: a certain instability, or an interface, which has no stability bits and is
     * therefore compared by instance. A dependence on a type parameter alone is not unstable.
     */
    fun unstableReason(): String? = when (this) {
        is Certain -> if (stable) null else reason ?: "it is unstable"
        is Unknown -> "'${classSymbol.classId.shortClassName.asString()}' is an interface without a stable marker, so Compose cannot know what implements it"
        is Parameter -> null
        is Combined -> elements.firstNotNullOfOrNull { it.unstableReason() }
    }

    companion object {
        val Stable: Stability = Certain(true)
        fun unstable(reason: String): Stability = Certain(false, reason)
    }
}

/**
 * The Compose compiler's stability inference (`StabilityInferencer` in
 * `androidx.compose.compiler.plugins.kotlin.analysis`) over FIR symbols, rule for rule:
 * primitives, strings, and function types are stable; enums, objects, and classes carrying a
 * `@StableMarker` annotation (or extending a type that does) are stable; the known stable
 * constructs (`Pair`, `Triple`, the kotlinx and Guava immutable collections, ...) are stable when
 * the masked type arguments are; Java classes and library classes not compiled with the Compose
 * compiler are unstable; interfaces are unknown; a class is otherwise as stable as its backing
 * fields and its superclass, and unstable at the first `var` without a delegate. A value class
 * is as stable as what it wraps unless it is marked. Star projections and reference cycles are
 * unstable. The project's own patterns from [stableTypes] are consulted after the annotations.
 */
class StabilityInferencer(private val session: FirSession, private val stableTypes: StableTypeMatchers) {
    private data class ClassKey(val symbol: FirRegularClassSymbol, val typeArguments: List<ConeTypeProjection?>)

    private val cache = mutableMapOf<ClassKey, Stability>()

    fun stabilityOf(type: ConeKotlinType): Stability = stabilityOf(type, emptyMap(), emptySet())

    private fun stabilityOf(type: ConeKotlinType, substitutions: Map<FirTypeParameterSymbol, ConeTypeProjection>, analyzing: Set<Any>): Stability {
        val expanded = type.lowerBoundIfFlexible().fullyExpandedType(session)
        return when {
            expanded is ConeErrorType -> Stability.unstable("'${type.renderReadable()}' does not resolve")
            expanded.classId == StandardClassIds.Unit || expanded.classId == StandardClassIds.String -> Stability.Stable
            expanded.isPrimitiveOrNullablePrimitive -> Stability.Stable
            expanded.isSomeFunctionType(session) -> Stability.Stable
            expanded is ConeTypeParameterType -> {
                val symbol = expanded.lookupTag.typeParameterSymbol
                val argument = substitutions[symbol]
                if (argument != null && symbol !in analyzing) stabilityOf(argument, substitutions, analyzing + symbol)
                else Stability.Parameter(symbol)
            }
            expanded is ConeClassLikeType -> {
                val symbol = expanded.toRegularClassSymbol(session)
                    ?: return Stability.unstable("'${type.renderReadable()}' does not resolve to a class")
                if (symbol.isInlineOrValue && symbol.classKind == ClassKind.CLASS) {
                    if (symbol.hasStableMarker()) return Stability.Stable
                    val underlying = symbol.underlyingValueType()
                        ?: return stabilityOfClass(symbol, substitutions + expanded.substitutionMap(symbol), analyzing)
                    return stabilityOf(underlying, substitutions, analyzing)
                }
                stabilityOfClass(symbol, substitutions + expanded.substitutionMap(symbol), analyzing)
            }
            else -> Stability.unstable("'${type.renderReadable()}' is not a class type")
        }
    }

    private fun stabilityOf(projection: ConeTypeProjection, substitutions: Map<FirTypeParameterSymbol, ConeTypeProjection>, analyzing: Set<Any>): Stability =
        when (projection) {
            is ConeStarProjection -> Stability.unstable("a star projection could be anything")
            is ConeKotlinTypeProjection -> stabilityOf(projection.type, substitutions, analyzing)
        }

    private fun stabilityOfClass(symbol: FirRegularClassSymbol, substitutions: Map<FirTypeParameterSymbol, ConeTypeProjection>, analyzing: Set<Any>): Stability {
        val key = ClassKey(symbol, symbol.typeParameterSymbols.map { substitutions[it] })
        cache[key]?.let { return it }
        val result = inferClass(symbol, key, substitutions, analyzing)
        cache[key] = result
        return result
    }

    private fun inferClass(symbol: FirRegularClassSymbol, key: ClassKey, substitutions: Map<FirTypeParameterSymbol, ConeTypeProjection>, analyzing: Set<Any>): Stability {
        val name = symbol.classId.shortClassName.asString()
        if (key in analyzing) return Stability.unstable("'$name' refers to itself")
        if (symbol.hasStableMarkedAncestor(HashSet())) return Stability.Stable
        if (symbol.isEnumClass || symbol.classKind == ClassKind.OBJECT) return Stability.Stable
        if (symbol.classId in StandardClassIds.primitiveTypes) return Stability.Stable
        if (symbol.isProtobufType()) return Stability.Stable

        val analyzingNow = analyzing + key
        val fqName = symbol.classId.asSingleFqName().asString()
        KnownStableConstructs.stableTypes[fqName]?.let { mask ->
            return Stability.Stable.applyTypeParameterMask(mask, symbol, substitutions, analyzingNow)
        }
        if (stableTypes.matches(fqName, symbol.superTypeFqNames())) {
            return Stability.Stable.applyTypeParameterMask(stableTypes.maskFor(fqName) ?: 0, symbol, substitutions, analyzingNow)
        }
        if (symbol.origin is FirDeclarationOrigin.Java || symbol.origin == FirDeclarationOrigin.Enhancement) {
            return Stability.unstable("'$name' is a Java class")
        }
        if (symbol.isInterface) return Stability.Unknown(symbol)
        if (!symbol.origin.fromSource && !symbol.hasAnnotation(STABILITY_INFERRED, session)) {
            return Stability.unstable("'$name' comes from a library that was not compiled with the Compose compiler")
        }

        var stability = Stability.Stable
        for (member in symbol.declarationSymbols) {
            if (member !is FirPropertySymbol) continue
            val fieldType = when {
                // The field of a delegated property holds the delegate; a library property has no delegate expression to read.
                member.isDelegatedProperty -> member.delegateType() ?: continue
                member.hasBackingField -> {
                    if (member.isVar) return Stability.unstable("'$name' has a mutable property '${member.name.asString()}'")
                    member.resolvedReturnType
                }
                else -> continue
            }
            val fieldStability = stabilityOf(fieldType, substitutions, analyzingNow)
            stability += fieldStability.unstableReason()?.let { reason ->
                Stability.unstable("'$name' has an unstable property '${member.name.asString()}': $reason")
            } ?: fieldStability
        }
        symbol.superClassType()?.let { superType ->
            val superStability = stabilityOf(superType, substitutions, analyzingNow)
            stability += superStability.unstableReason()?.let { reason ->
                Stability.unstable("'$name' extends an unstable class: $reason")
            } ?: superStability
        }
        return stability
    }

    /** Combines this with the stability of the type parameters whose bit is set in [mask] (`null`: all of them). */
    private fun Stability.applyTypeParameterMask(mask: Int?, symbol: FirRegularClassSymbol, substitutions: Map<FirTypeParameterSymbol, ConeTypeProjection>, analyzing: Set<Any>): Stability {
        val typeParameters = symbol.typeParameterSymbols
        if (mask == 0 || typeParameters.isEmpty()) return this
        val elements = typeParameters.mapIndexedNotNull { index, parameter ->
            if (index >= 32) return@mapIndexedNotNull null
            if (mask == null || mask and (1 shl index) != 0) {
                val argument = substitutions[parameter]
                if (argument != null) stabilityOf(argument, substitutions, analyzing) else Stability.Parameter(parameter)
            } else null
        }
        return this + Stability.Combined(elements)
    }

    /** The class's type parameters bound to the type's arguments, leaving out identity bindings (`Foo<T>` inside `Foo<T>`). */
    private fun ConeClassLikeType.substitutionMap(symbol: FirRegularClassSymbol): Map<FirTypeParameterSymbol, ConeTypeProjection> =
        symbol.typeParameterSymbols.zip(typeArguments.toList())
            .filter { (parameter, argument) -> (argument as? ConeTypeParameterType)?.lookupTag?.typeParameterSymbol != parameter }
            .toMap()

    private fun FirRegularClassSymbol.hasStableMarker(): Boolean = resolvedAnnotationsWithClassIds.any { annotation ->
        val annotationClass = annotation.toAnnotationClassLikeSymbol(session) ?: return@any false
        annotationClass.classId in KnownStableConstructs.stableMarkers || annotationClass.hasAnnotation(STABLE_MARKER, session)
    }

    private fun FirRegularClassSymbol.hasStableMarkedAncestor(visited: MutableSet<ClassId>): Boolean {
        if (!visited.add(classId)) return false
        if (hasStableMarker()) return true
        return resolvedSuperTypes.any { superType ->
            superType.classId != StandardClassIds.Any && superType.toRegularClassSymbol(session)?.hasStableMarkedAncestor(visited) == true
        }
    }

    private fun FirRegularClassSymbol.superClassType(): ConeKotlinType? = resolvedSuperTypes.firstOrNull { superType ->
        superType.classId != StandardClassIds.Any && superType.toRegularClassSymbol(session)?.classKind == ClassKind.CLASS
    }

    private fun FirRegularClassSymbol.superTypeFqNames(): List<String> =
        resolvedSuperTypes.mapNotNull { it.classId?.asSingleFqName()?.asString() }

    /** Protobuf messages are final classes generated over `GeneratedMessageLite` / `GeneratedMessage`, and immutable. */
    private fun FirRegularClassSymbol.isProtobufType(): Boolean {
        if (modality != Modality.FINAL) return false
        val parent = superClassType()?.classId?.asSingleFqName()?.asString() ?: return false
        return parent == "com.google.protobuf.GeneratedMessageLite" || parent == "com.google.protobuf.GeneratedMessage"
    }

    @OptIn(SymbolInternals::class)
    private fun FirPropertySymbol.delegateType(): ConeKotlinType? = fir.delegate?.resolvedType

    @OptIn(SymbolInternals::class)
    private fun FirRegularClassSymbol.underlyingValueType(): ConeKotlinType? =
        fir.primaryConstructorIfAny(session)?.valueParameterSymbols?.singleOrNull()?.resolvedReturnType

    private companion object {
        val STABLE_MARKER = ClassId(FqName("androidx.compose.runtime"), Name.identifier("StableMarker"))
        val STABILITY_INFERRED = ClassId(FqName("androidx.compose.runtime.internal"), Name.identifier("StabilityInferred"))
    }
}

/**
 * Types and annotations outside Compose that the Compose compiler knows to be stable, with the
 * mask of type arguments that take part (bit `i` set: argument `i` must be stable). Copied from
 * `androidx.compose.compiler.plugins.kotlin.analysis.KnownStableConstructs`.
 */
object KnownStableConstructs {
    val stableTypes: Map<String, Int> = mapOf(
        "kotlin.Pair" to 0b11,
        "kotlin.Triple" to 0b111,
        "kotlin.Comparator" to 0b1,
        "kotlin.Result" to 0b1,
        "kotlin.ranges.ClosedRange" to 0b1,
        "kotlin.ranges.ClosedFloatingPointRange" to 0b1,
        // Guava
        "com.google.common.collect.ImmutableList" to 0b1,
        "com.google.common.collect.ImmutableEnumMap" to 0b11,
        "com.google.common.collect.ImmutableMap" to 0b11,
        "com.google.common.collect.ImmutableEnumSet" to 0b1,
        "com.google.common.collect.ImmutableSet" to 0b1,
        // Kotlinx immutable
        "kotlinx.collections.immutable.ImmutableCollection" to 0b1,
        "kotlinx.collections.immutable.ImmutableList" to 0b1,
        "kotlinx.collections.immutable.ImmutableSet" to 0b1,
        "kotlinx.collections.immutable.ImmutableMap" to 0b11,
        "kotlinx.collections.immutable.PersistentCollection" to 0b1,
        "kotlinx.collections.immutable.PersistentList" to 0b1,
        "kotlinx.collections.immutable.PersistentSet" to 0b1,
        "kotlinx.collections.immutable.PersistentMap" to 0b11,
        // Dagger
        "dagger.Lazy" to 0b1,
        // Coroutines
        "kotlin.coroutines.EmptyCoroutineContext" to 0,
        // Java types
        "java.math.BigInteger" to 0,
        "java.math.BigDecimal" to 0,
        "java.util.Locale" to 0,
    )

    val stableMarkers: Set<ClassId> = setOf(
        ClassId(FqName("com.google.errorprone.annotations"), Name.identifier("Immutable")),
    )
}

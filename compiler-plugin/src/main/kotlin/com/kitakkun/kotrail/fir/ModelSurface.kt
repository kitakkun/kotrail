@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir

import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.stability.hasComposeStableMarker
import com.kitakkun.kotrail.inferred.PluginMetadata
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.hasBackingField
import org.jetbrains.kotlin.fir.declarations.utils.isData
import org.jetbrains.kotlin.fir.declarations.utils.isDelegatedProperty
import org.jetbrains.kotlin.fir.declarations.utils.isInlineOrValue
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeDefinitelyNotNullType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.ProjectionKind
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.name.Name

/**
 * What the rules that walk a model read of a class: `compose.noCallbackInModel` (the function-typed
 * properties a UI composable's parameter reaches), `narrowModelParameters` (the properties of a
 * data class parameter) and `compose.noUnstableParameter` (the fields of a class, `var` or not,
 * and its superclass). They read members Kotlin's incremental compilation does not count as part
 * of a class's ABI (a private property, a property added to the class body), and they reach
 * classes the use site never names (a model inside a model). A change there leaves the use site's
 * file as it was compiled.
 *
 * [Digester.digest] sums that surface up, for a class and every model class of this module it reaches, and
 * the IR writer puts it into the class's plugin metadata (see [PluginMetadata.SURFACE]), so that a
 * change of the surface is a change of the class for incremental compilation: the files that name
 * the class are compiled again, and the digest of a class that holds it changes in turn when its
 * own file is. The walks enumerate the properties through [properties] and [comparedNames], and
 * the digest covers at least what they read.
 */
object ModelSurface {
    /** Every property the class declares, in declaration order (also when loaded from a class file): what the walks enumerate. */
    fun properties(classSymbol: FirRegularClassSymbol): List<FirPropertySymbol> =
        classSymbol.declarationSymbols.filterIsInstance<FirPropertySymbol>()

    /**
     * The properties a data class's `equals` compares, by name: the parameters of its primary
     * constructor, each of which is a property of the same name; empty for any other class. Read
     * from the constructor rather than from the properties, since a class loaded from class files
     * does not say which of its properties the primary constructor declares.
     */
    fun comparedNames(classSymbol: FirRegularClassSymbol): Set<Name> {
        if (!classSymbol.isData) return emptySet()
        return classSymbol.declarationSymbols.filterIsInstance<FirConstructorSymbol>().firstOrNull { it.isPrimary }
            ?.valueParameterSymbols?.mapTo(LinkedHashSet()) { it.name }.orEmpty()
    }

    /** Whether the walks enter a class of this kind: a class or an interface, not an object, an enum or an annotation. */
    fun isModelKind(classSymbol: FirRegularClassSymbol): Boolean =
        classSymbol.classKind == ClassKind.CLASS || classSymbol.classKind == ClassKind.INTERFACE

    /**
     * Computes the digests of the classes of one session, each class's own surface and the
     * classes it reaches worked out once however many digests take them in.
     */
    class Digester(private val session: FirSession) {
        private val own = HashMap<FirRegularClassSymbol, List<String>>()
        private val reached = HashMap<FirRegularClassSymbol, List<FirRegularClassSymbol>>()

        /** The digest of [classSymbol]'s surface and of every model class of this module it reaches; see [lines]. */
        fun digest(classSymbol: FirRegularClassSymbol): String = PluginMetadata.digest(lines(classSymbol))

        /**
         * The surface of [root], then of every class of this module it reaches through the types
         * of its properties and its supertypes (type arguments included), each once, depth first.
         * A class's surface is its header (kind, data, value, modality, type parameters, a stable
         * marker), its supertypes, and one line per property in declaration order: name,
         * visibility, `val` or `var`, extension receiver, backing field, delegate, whether
         * `equals` compares it, its type with type aliases expanded, whether it is function-typed
         * and whether that function type is `@Composable` (a slot). Nothing in it depends on
         * whether a class was compiled from source or loaded from a class file, so a class's
         * digest changes only when what the walks read of it does.
         */
        fun lines(root: FirRegularClassSymbol): List<String> {
            val lines = ArrayList<String>()
            val visited = HashSet<FirRegularClassSymbol>()
            fun visit(classSymbol: FirRegularClassSymbol) {
                if (!visited.add(classSymbol)) return
                lines += own.getOrPut(classSymbol) { ownLines(classSymbol, session) }
                for (next in reached.getOrPut(classSymbol) { reachedClasses(classSymbol, session) }) visit(next)
            }
            visit(root)
            return lines
        }
    }

    private fun ownLines(classSymbol: FirRegularClassSymbol, session: FirSession): List<String> {
        val lines = ArrayList<String>()
        lines += "class ${classSymbol.classId.asString()} kind=${classSymbol.classKind} data=${classSymbol.isData} " +
            "value=${classSymbol.isInlineOrValue} modality=${classSymbol.modality} " +
            "typeParameters=${classSymbol.typeParameterSymbols.joinToString(",") { it.name.asString() }} " +
            "stableMarker=${classSymbol.hasComposeStableMarker(session)}"
        for (superType in classSymbol.resolvedSuperTypes) lines += "  supertype ${render(superType, session)}"
        val compared = comparedNames(classSymbol)
        for (property in properties(classSymbol)) {
            val type = property.resolvedReturnType.fullyExpandedType(session)
            val function = type.isSomeFunctionType(session)
            lines += "  property ${property.name.asString()} ${property.visibility} ${if (property.isVar) "var" else "val"}" +
                " receiver=${property.resolvedReceiverType?.let { render(it, session) } ?: "-"}" +
                " field=${property.hasBackingField} delegated=${property.isDelegatedProperty}" +
                " compared=${property.name in compared}" +
                " type=${render(type, session)} function=$function slot=${function && type.isComposable(session)}"
        }
        return lines
    }

    /** The model classes of this module that [classSymbol]'s properties and supertypes name, in order. */
    private fun reachedClasses(classSymbol: FirRegularClassSymbol, session: FirSession): List<FirRegularClassSymbol> {
        val reached = LinkedHashSet<FirRegularClassSymbol>()
        fun collect(type: ConeKotlinType) {
            val expanded = type.fullyExpandedType(session).lowerBoundIfFlexible()
            val classType = (expanded as? ConeDefinitelyNotNullType)?.original ?: expanded
            if (classType !is ConeClassLikeType) return
            classType.toRegularClassSymbol(session)
                ?.takeIf { it.isOfThisModule(session) && isModelKind(it) }
                ?.let { reached += it }
            for (argument in classType.typeArguments) (argument as? ConeKotlinTypeProjection)?.let { collect(it.type) }
        }
        for (property in properties(classSymbol)) collect(property.resolvedReturnType)
        for (superType in classSymbol.resolvedSuperTypes) collect(superType)
        return reached.toList()
    }

    private fun ConeKotlinType.isComposable(session: FirSession): Boolean =
        customAnnotations.any { it.toAnnotationClassId(session) == ComposeNames.COMPOSABLE }

    /** A type as the surface names it: the class id with arguments and nullability, `@Composable` on a slot, a type parameter by name. */
    private fun render(type: ConeKotlinType, session: FirSession): String {
        val expanded = type.fullyExpandedType(session).lowerBoundIfFlexible()
        val nullable = if (expanded.isMarkedNullable) "?" else ""
        return when (expanded) {
            is ConeClassLikeType -> {
                val arguments = expanded.typeArguments.takeIf { it.isNotEmpty() }?.joinToString(",", "<", ">") { render(it, session) }.orEmpty()
                val composable = if (expanded.isComposable(session)) "@Composable " else ""
                composable + expanded.lookupTag.classId.asString() + arguments + nullable
            }
            is ConeTypeParameterType -> expanded.lookupTag.name.asString() + nullable
            is ConeDefinitelyNotNullType -> render(expanded.original, session) + " & Any"
            else -> "?"
        }
    }

    private fun render(projection: ConeTypeProjection, session: FirSession): String = when (projection) {
        is ConeStarProjection -> "*"
        is ConeKotlinTypeProjection -> when (projection.kind) {
            ProjectionKind.IN -> "in " + render(projection.type, session)
            ProjectionKind.OUT -> "out " + render(projection.type, session)
            else -> render(projection.type, session)
        }
    }
}

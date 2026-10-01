package com.kitakkun.kotrail.fir.inferred

import com.kitakkun.kotrail.fir.inferred.InferredMetadata.strings
import com.kitakkun.kotrail.fir.isPrecompiled
import com.kitakkun.kotrail.inferred.FactsFileLookup
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeDefinitelyNotNullType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.name.Name

/**
 * The arguments of one fact, wherever they were read from: an annotation's arrays, or the
 * arrays a facts file recorded under the annotation's parameter names. A fact decodes itself from
 * either the same way.
 */
fun interface FactArguments {
    /** The strings of the array [parameter]; empty when absent. */
    fun strings(parameter: Name): List<String>

    companion object {
        fun of(annotation: FirAnnotation): FactArguments = FactArguments { annotation.strings(it) }

        fun of(arguments: Map<String, List<String>>): FactArguments = FactArguments { arguments[it.asString()].orEmpty() }
    }
}

/**
 * How a declaration is named in a facts file, computed from its FIR symbol. The IR writer computes
 * it from the FIR declaration attached to each IR declaration and the reader from the classpath
 * symbol it is asked about, with this one helper on both sides, so the two cannot drift apart.
 *
 * - [id]: the package, `/`, then the class-qualified name: `com.example/Foo.bar` for a member,
 *   `com.example/bar` for a top-level callable, `com.example/Foo` (or `com.example/Foo.Inner`)
 *   for a class. Local declarations have none.
 * - [signature]: empty for a class and for a callable without parameters; otherwise one item per
 *   context parameter (`context:<type>`), then the extension receiver (`receiver:<type>`), then
 *   each value parameter (`<type>`), joined by `,`. A type is the class id of its fully expanded
 *   form (type aliases expanded, nullability and arguments dropped, `kotlin/collections/List`,
 *   `kotlin/IntArray` for `vararg Int`), `*` for a type parameter, `?` for anything else.
 */
object FactKeys {
    fun id(symbol: FirBasedSymbol<*>): String? = when (symbol) {
        is FirClassLikeSymbol<*> -> symbol.classId.takeUnless { it.isLocal }?.let { "${it.packageFqName.asString()}/${it.relativeClassName.asString()}" }
        is FirCallableSymbol<*> -> symbol.callableId?.takeUnless { it.isLocal }?.let { callableId ->
            val owner = callableId.className?.let { "${it.asString()}." }.orEmpty()
            "${callableId.packageName.asString()}/$owner${callableId.callableName.asString()}"
        }
        else -> null
    }

    fun signature(symbol: FirBasedSymbol<*>, session: FirSession): String {
        if (symbol !is FirCallableSymbol<*>) return ""
        val context = symbol.contextParameterSymbols.map { "context:" + render(it.resolvedReturnType, session) }
        val receiver = listOfNotNull(symbol.resolvedReceiverType?.let { "receiver:" + render(it, session) })
        val values = (symbol as? FirFunctionSymbol<*>)?.valueParameterSymbols?.map { render(it.resolvedReturnType, session) }.orEmpty()
        return (context + receiver + values).joinToString(",")
    }

    fun render(type: ConeKotlinType, session: FirSession): String =
        when (val expanded = type.fullyExpandedType(session).lowerBoundIfFlexible()) {
            is ConeClassLikeType -> expanded.lookupTag.classId.asString()
            is ConeTypeParameterType -> "*"
            is ConeDefinitelyNotNullType -> render(expanded.original, session)
            else -> "?"
        }
}

/**
 * The facts in the facts files (see [com.kitakkun.kotrail.inferred.FactsFile]) for the
 * [InferredFactService]s to read before a declaration's own annotations: the module's own file
 * (the `ownFacts` plugin option) for a declaration that an incremental compilation loaded back
 * from the output directory, and the files of the compilation's dependencies (the `facts` plugin
 * option) for that and for a library declaration; see [FactsFileLookup] for the order. A source
 * declaration finds nothing: its body is analyzed. The indexes are built once, lazily, and shared
 * by every session; a compilation handed no facts files finds nothing.
 */
class FactsFileComponent(session: FirSession, private val lookup: FactsFileLookup) : FirExtensionSessionComponent(session) {
    /** The arguments of fact [name] a facts file recorded for [symbol], or null. */
    fun arguments(symbol: FirBasedSymbol<*>, name: String): FactArguments? {
        val id = FactKeys.id(symbol) ?: return null
        val origin = when {
            symbol.origin.fromSource -> FactsFileLookup.Origin.SOURCE
            symbol.isPrecompiled(session) -> FactsFileLookup.Origin.PRECOMPILED
            else -> FactsFileLookup.Origin.LIBRARY
        }
        return lookup.lookup(id, FactKeys.signature(symbol, session), name, origin)?.let { FactArguments.of(it) }
    }

    companion object {
        fun getFactory(lookup: FactsFileLookup): Factory = Factory { session -> FactsFileComponent(session, lookup) }
    }
}

val FirSession.factsFiles: FactsFileComponent by FirSession.sessionComponentAccessor()

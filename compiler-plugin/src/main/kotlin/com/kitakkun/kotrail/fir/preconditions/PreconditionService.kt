@file:OptIn(SymbolInternals::class, DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.preconditions

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.inferred.InferredFactService
import com.kitakkun.kotrail.fir.inferred.InferredMetadata.strings
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

import com.kitakkun.kotrail.preconditions.Cond
import com.kitakkun.kotrail.preconditions.CondParser
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertyGetter
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.declarations.utils.fromPrimaryConstructor
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.coneType

/**
 * Per-session knowledge of which preconditions a callable imposes on its parameters.
 *
 * For a declaration compiled from source the conditions are extracted from the leading `require`
 * / `check` / `requireNotNull` calls of the body (or of a class's `init` blocks, for its primary
 * constructor). For anything else they are read from the `@InferredPreconditions` annotation
 * that the IR writer stamped when the declaring module was compiled. A hand-written
 * `@InferredPreconditions` wins over analysis in both cases.
 *
 * Bodies are released after Fir2Ir, so the checkers warm this cache for every source
 * declaration; the IR writer only reads what was cached.
 */
class PreconditionService(session: FirSession) : FirExtensionSessionComponent(session) {
    /** The fact about a function: the conditions its leading `require` / `check` calls impose. */
    val functions: InferredFactService<FirNamedFunctionSymbol, List<Cond>> = Fact { symbol ->
        val function = symbol.fir as? FirNamedFunction ?: return@Fact emptyList()
        val params = function.valueParameters.associate { it.symbol as FirBasedSymbol<*> to it.name.asString() }
        val body = function.body ?: return@Fact emptyList()
        extract(body.statements, params)
    }

    /** The fact about a class: the conditions its `init` blocks impose on the primary constructor's parameters. */
    val classes: InferredFactService<FirRegularClassSymbol, List<Cond>> = Fact { symbol -> computeForClass(symbol) }

    /** The conditions a call to [callee] must satisfy. Constructors other than the primary one have none. */
    fun preconditionsOf(callee: FirFunctionSymbol<*>): List<Cond> = when (callee) {
        is FirNamedFunctionSymbol -> functions.of(callee)
        is FirConstructorSymbol -> {
            val classSymbol = callee.resolvedReturnTypeRef.coneType.toRegularClassSymbol(session)
            if (classSymbol == null || !callee.isPrimary) emptyList() else preconditionsOfClass(classSymbol)
        }
        else -> emptyList()
    }

    fun preconditionsOfClass(classSymbol: FirRegularClassSymbol): List<Cond> = classes.of(classSymbol)

    /** One fact shape for functions and classes: a hand-written or inferred annotation always wins over the body. */
    private inner class Fact<S : FirBasedSymbol<*>>(private val analysis: (S) -> List<Cond>) : InferredFactService<S, List<Cond>>(session) {
        override val rule: KotrailRule get() = KotrailRule.PRECONDITIONS
        override val annotation: ClassId get() = PreconditionNames.INFERRED_PRECONDITIONS
        override val parameters: List<Name> get() = listOf(PreconditionNames.CONDITIONS_PARAM)
        override val empty: List<Cond> get() = emptyList()
        override val metadataForSource: Boolean get() = true
        override fun decode(annotation: FirAnnotation): List<Cond> = annotation.strings(PreconditionNames.CONDITIONS_PARAM).mapNotNull { CondParser.parse(it) }
        override fun encode(value: List<Cond>): List<List<String>>? = value.mapNotNull { it.render() }.takeIf { it.isNotEmpty() }?.let { listOf(it) }
        override fun analyze(symbol: S): List<Cond> = analysis(symbol)
    }

    private fun computeForClass(symbol: FirRegularClassSymbol): List<Cond> {
        val klass = symbol.fir as? FirRegularClass ?: return emptyList()
        val primary = klass.declarations.filterIsInstance<FirConstructor>().firstOrNull { it.isPrimary } ?: return emptyList()
        val params = HashMap<FirBasedSymbol<*>, String>()
        for (parameter in primary.valueParameters) params[parameter.symbol] = parameter.name.asString()
        for (declaration in klass.declarations) {
            val property = declaration as? FirProperty ?: continue
            if (property.fromPrimaryConstructor != true || !property.isVal) continue
            val getter = property.getter
            if (getter != null && getter !is FirDefaultPropertyGetter) continue
            params[property.symbol] = property.name.asString()
        }
        val result = mutableListOf<Cond>()
        for (declaration in klass.declarations) {
            val initializer = declaration as? FirAnonymousInitializer ?: continue
            val body = initializer.body ?: continue
            result += extract(body.statements, params)
        }
        return result
    }

    /** The leading run of precondition calls in [statements], converted where possible. */
    private fun extract(statements: List<FirStatement>, params: Map<FirBasedSymbol<*>, String>): List<Cond> {
        val converter = CondConverter(session, params)
        val result = mutableListOf<Cond>()
        for (statement in statements) {
            val call = (statement as? FirFunctionCall)
                ?: ((statement as? FirProperty)?.initializer as? FirFunctionCall)
                ?: break
            val callableId = call.calleeReference.toResolvedNamedFunctionSymbol()?.callableId ?: break
            val argument = call.arguments.firstOrNull()?.unwrapArgument() ?: break
            val condition = when (callableId) {
                in PreconditionNames.CONDITION_CALLS -> converter.convert(argument)
                in PreconditionNames.NOT_NULL_CALLS -> converter.convert(argument)?.let {
                    Cond.Equals(negated = true, it, Cond.Const(com.kitakkun.kotrail.preconditions.Value.NullV))
                }
                else -> break
            }
            // A condition that mentions no parameter says nothing about the call site.
            if (condition != null && condition.parameters().isNotEmpty()) result += condition
        }
        return result
    }

}

val FirSession.preconditionService: PreconditionService by FirSession.sessionComponentAccessor()

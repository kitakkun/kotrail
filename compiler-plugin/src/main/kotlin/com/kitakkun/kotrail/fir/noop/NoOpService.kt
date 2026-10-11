@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.noop

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.qualifierClassId
import com.kitakkun.kotrail.fir.inferred.FactArguments
import com.kitakkun.kotrail.fir.inferred.InferredFactService
import com.kitakkun.kotrail.fir.preconditions.CondConverter
import com.kitakkun.kotrail.inferred.InferredFactNames
import com.kitakkun.kotrail.preconditions.Cond
import com.kitakkun.kotrail.preconditions.CondParser
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.containingClassLookupTag
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.isFinal
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.impl.FirUnitExpression
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

/** When a call does nothing: as soon as one of [conditions] holds. [hint] is the sentence that says what to do instead, or empty. */
data class NoOpConditions(val conditions: List<Cond>, val hint: String)

/**
 * The inferred fact `noOpWhen`: the argument conditions under which a function that returns
 * `Unit` returns at once, having done nothing.
 *
 * For a declaration compiled from source the conditions are read from the guards that open its
 * body, `if (bytes.isEmpty()) return`, or a `when` without subject whose leading branches are
 * `condition -> return`; several guards in a row each count. A guard is a statement nothing
 * precedes but other guards, whose branch holds a bare `return` and nothing else, and whose
 * condition fits the precondition language, which keeps it free of side effects; the first
 * statement that is not one ends the run. Only a function no override can replace is read, since
 * the fact belongs to the declaration called.
 *
 * For a declaration of another module the conditions are what its module recorded: the
 * `noOpWhen` entry of its facts file on the JVM, `@InferredNoOpWhen` in a klib, both written by
 * the IR writer in the same language. The annotation, which may also be written by hand on a
 * source declaration, wins over the body; a library function without either falls back on
 * [NoOpNames.KNOWN_FUNCTIONS].
 */
class NoOpService(session: FirSession) : InferredFactService<FirNamedFunctionSymbol, NoOpConditions>(session) {
    override val rules: List<KotrailRule> get() = listOf(KotrailRule.NO_OP_CALL)
    override val annotation: ClassId get() = NoOpNames.INFERRED_NO_OP_WHEN
    override val factName: String get() = InferredFactNames.NO_OP_WHEN
    override val parameters: List<Name> get() = listOf(NoOpNames.CONDITIONS_PARAM)
    override val empty: NoOpConditions get() = NoOpConditions(emptyList(), "")
    override val metadataForSource: Boolean get() = true

    override fun knowledge(symbol: FirNamedFunctionSymbol): NoOpConditions? {
        val candidates = NoOpNames.KNOWN_FUNCTIONS.filter { it.callableId == symbol.callableId }
        if (candidates.isEmpty() || symbol.resolvedReceiverType != null) return null
        val parameterTypes = symbol.valueParameterSymbols.map { it.resolvedReturnType.fullyExpandedType(session).classId }
        val entry = candidates.firstOrNull { it.parameterTypes == parameterTypes } ?: return null
        return NoOpConditions(entry.conditions.mapNotNull { CondParser.parse(it) }, entry.hint)
    }

    override fun decode(arguments: FactArguments): NoOpConditions =
        NoOpConditions(arguments.strings(NoOpNames.CONDITIONS_PARAM).mapNotNull { CondParser.parse(it) }, "")

    override fun encode(value: NoOpConditions): List<List<String>>? =
        value.conditions.mapNotNull { it.render() }.takeIf { it.isNotEmpty() }?.let { listOf(it) }

    override fun analyze(symbol: FirNamedFunctionSymbol): NoOpConditions {
        val function = symbol.fir as? FirNamedFunction ?: return empty
        if (!function.returnTypeRef.coneType.isUnit || !symbol.cannotBeOverridden()) return empty
        val body = function.body ?: return empty
        val params = function.valueParameters.associate { it.symbol as FirBasedSymbol<*> to it.name.asString() }
        val converter = CondConverter(session, params)
        val conditions = mutableListOf<Cond>()
        for (statement in body.statements) {
            val guard = statement as? FirWhenExpression ?: break
            if (!readGuard(guard, symbol, converter, conditions)) break
        }
        return NoOpConditions(conditions, "")
    }

    /**
     * Adds to [conditions] those of the leading branches of [guard] that only return; whether every
     * branch did, so that the statement after it is still reached only by doing nothing.
     */
    private fun readGuard(guard: FirWhenExpression, function: FirNamedFunctionSymbol, converter: CondConverter, conditions: MutableList<Cond>): Boolean {
        if (guard.subjectVariable != null) return false
        for (branch in guard.branches) {
            if (branch.condition is FirElseIfTrueCondition) return false
            if (!branch.result.isBareReturnFrom(function)) return false
            val condition = converter.convert(branch.condition) ?: return false
            if (condition.parameters().isNotEmpty()) conditions += condition
        }
        return true
    }

    /** `return` or `return Unit` from [function], and nothing else. */
    private fun FirBlock.isBareReturnFrom(function: FirNamedFunctionSymbol): Boolean {
        val statement = statements.singleOrNull() as? FirReturnExpression ?: return false
        if (statement.target.labeledElement.symbol != function) return false
        val result = statement.result
        return result is FirUnitExpression || (result is FirResolvedQualifier && result.qualifierClassId == StandardClassIds.Unit)
    }

    /** Final, or a member of a final class that is not an enum: no override can run in its place. */
    private fun FirNamedFunctionSymbol.cannotBeOverridden(): Boolean {
        if (isFinal) return true
        val owner = containingClassLookupTag()?.toRegularClassSymbol(session) ?: return true
        return owner.isFinal && owner.classKind != ClassKind.ENUM_CLASS
    }
}

val FirSession.noOpService: NoOpService by FirSession.sessionComponentAccessor()

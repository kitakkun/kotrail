package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.test.isTestFunction
import com.kitakkun.kotrail.fir.test.isTestSourcePath
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.unsubstitutedScope
import org.jetbrains.kotlin.fir.declarations.isDeprecationLevelHidden
import org.jetbrains.kotlin.fir.declarations.isJavaOrEnhancement
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.isVisible
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.scopes.getFunctions
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visibilityChecker
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.text
import org.jetbrains.kotlin.types.ConstantValueKind

/**
 * Reports a positive integer literal passed as a number of time units to a function that also
 * takes the same value as a `kotlin.time.Duration`:
 *
 * ```kotlin
 * delay(500)                                  // reported: delay(duration: Duration) exists
 * flow.debounce(300)                          // reported
 * delay(500.milliseconds)                     // fine: the unit is written
 * delay(PollInterval)                         // fine: the value is named
 * Thread.sleep(1500)                          // fine: no Duration overload to call instead
 * poll(interval = 250)                        // fine unless poll(interval: Duration) exists
 * process.waitFor(15, TimeUnit.SECONDS)       // fine: the TimeUnit beside it states the unit
 * delay(0)                                    // fine here: noOpCall reports a wait that does nothing
 * ```
 *
 * The overload is a function of the same name in the callee's package or class, visible from
 * the call and not hidden by a deprecation, with the same extension receiver, the same number of
 * parameters, `Duration` at the literal's position and every other parameter of the same type.
 * A call that also passes a `TimeUnit` or a `ChronoUnit` already carries its unit and is skipped
 * whole. Only literals are reported, through a unary minus or plus written on one: any other
 * expression, a constant or a pass-through parameter, already has a name. Zero and negative
 * values wait nothing and are left to [KotrailRule.NO_OP_CALL]. The suggestion is in
 * milliseconds unless the parameter's name ends in another unit (`timeoutSeconds`). Test
 * functions and test source sets are not looked at.
 */
object PreferDurationChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    private val DURATION = ClassId(FqName("kotlin.time"), Name.identifier("Duration"))
    private val UNIT_TYPES = setOf(
        ClassId(FqName("java.util.concurrent"), Name.identifier("TimeUnit")),
        ClassId(FqName("java.time.temporal"), Name.identifier("ChronoUnit")),
    )
    private val INTEGER_TYPES = setOf(StandardClassIds.Int, StandardClassIds.Long)
    private val INTEGER_KINDS = setOf(
        ConstantValueKind.IntegerLiteral, ConstantValueKind.Int, ConstantValueKind.Long,
        ConstantValueKind.Short, ConstantValueKind.Byte,
    )
    private val UNARY_MINUS = Name.identifier("unaryMinus")
    private val UNARY_PLUS = Name.identifier("unaryPlus")

    /** Parameter name suffixes, lowercased, and the `Duration` property that states the same unit. */
    private val UNIT_SUFFIXES = listOf(
        "millis" to "milliseconds", "ms" to "milliseconds", "milliseconds" to "milliseconds",
        "nanos" to "nanoseconds", "nanoseconds" to "nanoseconds",
        "micros" to "microseconds", "microseconds" to "microseconds",
        "seconds" to "seconds", "secs" to "seconds", "minutes" to "minutes", "hours" to "hours", "days" to "days",
    )

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val session = context.session
        val config = session.kotrailConfig
        if (!config.isEnabled(KotrailRule.PREFER_DURATION)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        if (callee.isJavaOrEnhancement) return
        val mapping = expression.resolvedArgumentMapping ?: return
        if (mapping.keys.any { it.resolvedType.fullyExpandedType().classId in UNIT_TYPES }) return

        var inTestChecked = false
        for ((argument, parameter) in mapping) {
            val literal = argument.unwrapArgument()
            val value = literal.integerLiteralValue() ?: continue
            if (value <= 0L) continue
            if (parameter.symbol.resolvedReturnType.fullyExpandedType().classId !in INTEGER_TYPES) continue
            val at = literal.source ?: continue
            if (at.kind is KtFakeSourceElementKind) continue
            val text = at.text?.toString() ?: continue
            if (!inTestChecked) {
                if (inTest(session, config.test.annotations)) return
                inTestChecked = true
            }
            val position = callee.valueParameterSymbols.indexOf(parameter.symbol)
            if (position < 0 || !callee.hasDurationOverload(position, expression)) continue
            val number = if (literal is FirLiteralExpression) text.trimEnd('L', 'l') else value.toString()
            reportKotrail(at, KotrailDiagnostics.PREFER_DURATION, text, "$number.${unitOf(parameter.name.asString())}")
        }
    }

    /** The value of an integer literal, through a unary minus or plus written on it; null for anything else. */
    private fun FirExpression.integerLiteralValue(): Long? {
        if (this is FirFunctionCall) {
            if (argumentList.arguments.isNotEmpty()) return null
            val operand = explicitReceiver?.integerLiteralValue() ?: return null
            return when (calleeReference.name) {
                UNARY_MINUS -> -operand
                UNARY_PLUS -> operand
                else -> null
            }
        }
        if (this !is FirLiteralExpression || kind !in INTEGER_KINDS) return null
        return (value as? Number)?.toLong()
    }

    /** The `Duration` property for the unit the parameter's name ends in; milliseconds when it names none. */
    private fun unitOf(parameterName: String): String {
        val lower = parameterName.lowercase()
        return UNIT_SUFFIXES.firstOrNull { (suffix, _) -> lower.endsWith(suffix) }?.second ?: "milliseconds"
    }

    /**
     * Whether a function of the same name next to this one, callable from here, takes a `Duration` at
     * [position] and otherwise the same receiver and parameters.
     */
    context(context: CheckerContext)
    private fun FirNamedFunctionSymbol.hasDurationOverload(position: Int, call: FirFunctionCall): Boolean {
        val session = context.session
        val id = callableId
        val classId = id.classId
        val siblings: List<FirNamedFunctionSymbol> = if (classId == null) {
            session.symbolProvider.getTopLevelFunctionSymbols(id.packageName, id.callableName)
        } else {
            val owner = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirClassSymbol<*> ?: return false
            owner.unsubstitutedScope(context).getFunctions(id.callableName)
        }
        val own = valueParameterSymbols.map { it.resolvedReturnType.shape(session, typeParameterSymbols) }
        val ownReceiver = resolvedReceiverType?.shape(session, typeParameterSymbols)
        val file = context.containingFileSymbol ?: return false
        return siblings.any { sibling ->
            if (sibling == this) return@any false
            val parameters = sibling.valueParameterSymbols
            if (parameters.size != own.size) return@any false
            if (parameters[position].resolvedReturnType.fullyExpandedType().classId != DURATION) return@any false
            if (sibling.resolvedReceiverType?.shape(session, sibling.typeParameterSymbols) != ownReceiver) return@any false
            val sameOthers = parameters.indices.all { index ->
                index == position || parameters[index].resolvedReturnType.shape(session, sibling.typeParameterSymbols) == own[index]
            }
            sameOthers && !sibling.isDeprecationLevelHidden(session) &&
                session.visibilityChecker.isVisible(sibling, session, file, context.containingDeclarations, call.dispatchReceiver)
        }
    }

    /**
     * A type written so that two overloads compare equal when they declare the same type: the
     * function's own type parameters by position, since each overload has its own symbols for them.
     */
    private fun ConeKotlinType.shape(session: FirSession, typeParameters: List<FirTypeParameterSymbol>): String {
        val type = fullyExpandedType(session)
        val nullable = if (type.isMarkedNullable) "?" else ""
        return when (type) {
            is ConeTypeParameterType -> {
                val index = typeParameters.indexOf(type.lookupTag.typeParameterSymbol)
                (if (index >= 0) "#$index" else type.lookupTag.name.asString()) + nullable
            }
            is ConeClassLikeType -> {
                val arguments = type.typeArguments.joinToString(",") { argument ->
                    when (argument) {
                        is ConeStarProjection -> "*"
                        is ConeKotlinTypeProjection -> argument.kind.name + " " + argument.type.shape(session, typeParameters)
                    }
                }
                "${type.lookupTag.classId}<$arguments>$nullable"
            }
            else -> type.renderReadable()
        }
    }

    context(context: CheckerContext)
    private fun inTest(session: FirSession, annotations: List<String>): Boolean {
        val path = context.containingFileSymbol?.sourceFile?.path
        if (path != null && isTestSourcePath(path)) return true
        return context.containingDeclarations.any { it is FirNamedFunctionSymbol && it.isTestFunction(session, annotations) }
    }
}

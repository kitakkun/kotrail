@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.preconditions

import com.kitakkun.kotrail.preconditions.Cond
import com.kitakkun.kotrail.preconditions.Value
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertyGetter
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.utils.isConst
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isLateInit
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirCheckNotNullCall
import org.jetbrains.kotlin.fir.expressions.FirComparisonExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirFunctionCallOrigin
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.contracts.description.LogicOperationKind
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.ConstantValueKind

/**
 * Turns a resolved FIR expression into a [Cond], following variables to their initializers.
 *
 * Symbols listed in [params] become [Cond.Param] nodes; every other reference is either folded
 * (a literal, a `const val`, a `val` with an initializer that folds, a local `val`) or gives up
 * with `null`. Anything the evaluator could not decide soundly (a `var`, a custom getter, a
 * call to an ordinary function, a `this`) also yields `null`, which callers treat as "unknown".
 */
class CondConverter(
    private val session: FirSession,
    private val params: Map<FirBasedSymbol<*>, String>,
) {
    private val visiting = HashSet<FirBasedSymbol<*>>()

    fun convert(expression: FirExpression): Cond? = when (expression) {
        is FirSmartCastExpression -> convert(expression.originalExpression)
        is FirWrappedArgumentExpression -> convert(expression.expression)
        is FirCheckNotNullCall -> expression.arguments.singleOrNull()?.let { convert(it) }
        is FirLiteralExpression -> literal(expression)
        is FirStringConcatenationCall -> concatenation(expression)
        is FirBooleanOperatorExpression -> {
            val left = convert(expression.leftOperand)
            val right = convert(expression.rightOperand)
            when {
                left == null || right == null -> null
                expression.kind == LogicOperationKind.AND -> Cond.And(left, right)
                else -> Cond.Or(left, right)
            }
        }
        is FirComparisonExpression -> comparison(expression)
        is FirEqualityOperatorCall -> equality(expression)
        is FirElvisExpression -> {
            val left = convert(expression.lhs)
            val right = convert(expression.rhs)
            if (left == null || right == null) null else Cond.Elvis(left, right)
        }
        is FirWhenExpression -> ifExpression(expression)
        is FirFunctionCall -> call(expression)
        is FirPropertyAccessExpression -> propertyAccess(expression)
        else -> null
    }

    private fun literal(expression: FirLiteralExpression): Cond? {
        val value = expression.value
        val v: Value = when (expression.kind) {
            ConstantValueKind.Boolean -> Value.BoolV(value as Boolean)
            ConstantValueKind.Char -> Value.CharV(value as Char)
            ConstantValueKind.Byte -> Value.IntV((value as Number).toInt())
            ConstantValueKind.Short -> Value.IntV((value as Number).toInt())
            ConstantValueKind.Int -> Value.IntV((value as Number).toInt())
            ConstantValueKind.Long -> Value.LongV((value as Number).toLong())
            ConstantValueKind.Float -> Value.FloatV((value as Number).toFloat())
            ConstantValueKind.Double -> Value.DoubleV((value as Number).toDouble())
            ConstantValueKind.String -> Value.StrV(value as String)
            ConstantValueKind.Null -> Value.NullV
            else -> return null
        }
        return Cond.Const(v)
    }

    private fun concatenation(expression: FirStringConcatenationCall): Cond? {
        var result: Cond = Cond.Const(Value.StrV(""))
        for (argument in expression.arguments) {
            val part = convert(argument) ?: return null
            result = Cond.Arith(Cond.ArithOp.PLUS, result, part)
        }
        return result
    }

    private fun comparison(expression: FirComparisonExpression): Cond? {
        val op = when (expression.operation) {
            FirOperation.LT -> Cond.CompareOp.LT
            FirOperation.LT_EQ -> Cond.CompareOp.LE
            FirOperation.GT -> Cond.CompareOp.GT
            FirOperation.GT_EQ -> Cond.CompareOp.GE
            else -> return null
        }
        val call = expression.compareToCall
        val leftExpression = call.explicitReceiver ?: call.dispatchReceiver ?: return null
        val rightExpression = call.arguments.singleOrNull() ?: return null
        val left = convert(leftExpression) ?: return null
        val right = convert(rightExpression) ?: return null
        return Cond.Compare(op, left, right)
    }

    private fun equality(expression: FirEqualityOperatorCall): Cond? {
        val negated = when (expression.operation) {
            FirOperation.EQ -> false
            FirOperation.NOT_EQ -> true
            else -> return null
        }
        val (leftExpression, rightExpression) = expression.arguments.takeIf { it.size == 2 } ?: return null
        val left = convert(leftExpression) ?: return null
        val right = convert(rightExpression) ?: return null
        return Cond.Equals(negated, left, right)
    }

    private fun ifExpression(expression: FirWhenExpression): Cond? {
        if (expression.subjectVariable != null) return null
        val branches = expression.branches.takeIf { it.size == 2 } ?: return null
        if (branches[1].condition !is FirElseIfTrueCondition) return null
        val condition = convert(branches[0].condition) ?: return null
        val thenBranch = singleExpression(branches[0].result)?.let { convert(it) } ?: return null
        val elseBranch = singleExpression(branches[1].result)?.let { convert(it) } ?: return null
        return Cond.If(condition, thenBranch, elseBranch)
    }

    private fun singleExpression(block: FirBlock): FirExpression? = block.statements.singleOrNull() as? FirExpression

    private fun call(expression: FirFunctionCall): Cond? {
        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return null
        val callableId = callee.callableId
        val name = callableId.callableName.asString()
        val receiver = expression.explicitReceiver ?: expression.dispatchReceiver ?: expression.extensionReceiver
        val classId = callableId.classId

        // listOf(1, 2) / emptyList(): folded to a constant list when every element folds.
        if (callableId == LIST_OF || callableId == EMPTY_LIST) {
            val elements = expression.arguments.flatMap { argument ->
                if (argument is FirVarargArgumentsExpression) argument.arguments else listOf(argument)
            }
            val values = elements.map { convert(it)?.evaluate(emptyMap()) ?: return null }
            return Cond.Const(Value.ListV(values))
        }

        val arithmetic = ARITHMETIC[name]
        if (arithmetic != null && classId != null && classId.asSingleFqName() in ARITHMETIC_RECEIVERS) {
            val left = receiver?.let { convert(it) } ?: return null
            val right = expression.arguments.singleOrNull()?.let { convert(it) } ?: return null
            return Cond.Arith(arithmetic, left, right)
        }
        if (name == "unaryMinus" && classId != null && classId.asSingleFqName() in NUMERIC_RECEIVERS) {
            return receiver?.let { convert(it) }?.let { Cond.Negate(it) }
        }
        if (callableId == BOOLEAN_NOT) {
            return receiver?.let { convert(it) }?.let { Cond.Not(it) }
        }
        val conversion = CONVERSIONS[name]
        if (conversion != null && classId != null && classId.asSingleFqName() in NUMERIC_RECEIVERS) {
            return receiver?.let { convert(it) }?.let { Cond.Convert(it, conversion) }
        }
        if (name == "contains" && expression.origin == FirFunctionCallOrigin.Operator) {
            return rangeCheck(expression)
        }
        val member = Cond.MemberKind.byText(name)
        if (member != null && member.isCall && callableId.packageName in MEMBER_PACKAGES) {
            val subject = receiver?.let { convert(it) } ?: return null
            return Cond.Member(subject, member)
        }
        return null
    }

    /** `a in b..c`, `a in b..<c`, `a in b until c`: FIR spells these as `(b..c).contains(a)`. */
    private fun rangeCheck(expression: FirFunctionCall): Cond? {
        val range = expression.explicitReceiver as? FirFunctionCall ?: return null
        val rangeCallee = range.calleeReference.toResolvedNamedFunctionSymbol() ?: return null
        val inclusive = when (rangeCallee.callableId.callableName.asString()) {
            "rangeTo" -> true
            "rangeUntil", "until" -> false
            else -> return null
        }
        val lowExpression = range.explicitReceiver ?: range.dispatchReceiver ?: range.extensionReceiver ?: return null
        val subject = expression.arguments.singleOrNull()?.let { convert(it) } ?: return null
        val low = convert(lowExpression) ?: return null
        val high = range.arguments.singleOrNull()?.let { convert(it) } ?: return null
        return Cond.InRange(negated = false, subject, low, high, inclusive)
    }

    private fun propertyAccess(expression: FirPropertyAccessExpression): Cond? {
        val symbol = expression.calleeReference.toResolvedCallableSymbol() ?: return null
        val receiver = expression.explicitReceiver
        if (symbol in params && (receiver == null || receiver is FirThisReceiverExpression)) {
            return Cond.Param(params.getValue(symbol))
        }
        if (symbol !is FirPropertySymbol) return null
        val callableId = symbol.callableId
        if (callableId == STRING_LENGTH) {
            return receiver?.let { convert(it) }?.let { Cond.Member(it, Cond.MemberKind.LENGTH) }
        }
        if (callableId != null && callableId.callableName == SIZE && callableId.packageName == COLLECTIONS) {
            return receiver?.let { convert(it) }?.let { Cond.Member(it, Cond.MemberKind.SIZE) }
        }
        return foldProperty(symbol)
    }

    /** The value of a `val` that can only ever hold its initializer. */
    private fun foldProperty(symbol: FirPropertySymbol): Cond? {
        if (!symbol.isVal || symbol.isLateInit || symbol.isExpect) return null
        if (symbol.delegate != null) return null
        val isLocal = symbol.isLocal
        if (!isLocal && !symbol.isConst) {
            // A member or top-level val: its initializer is the value only when nothing can override
            // it and no getter computes something else.
            if (symbol.modality != Modality.FINAL) return null
            val getter = symbol.getterSymbol?.fir
            if (getter != null && getter !is FirDefaultPropertyGetter) return null
        }
        if (!visiting.add(symbol)) return null
        try {
            if (!isLocal) symbol.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
            val initializer = symbol.fir.initializer ?: return null
            return convert(initializer)
        } finally {
            visiting.remove(symbol)
        }
    }

    companion object {
        private val KOTLIN = FqName("kotlin")
        private val COLLECTIONS = FqName("kotlin.collections")
        private val SIZE = Name.identifier("size")
        private val LIST_OF = CallableId(COLLECTIONS, Name.identifier("listOf"))
        private val EMPTY_LIST = CallableId(COLLECTIONS, Name.identifier("emptyList"))
        private val BOOLEAN_NOT = CallableId(KOTLIN.child(Name.identifier("Boolean")), Name.identifier("not"))
        private val STRING_LENGTH = CallableId(KOTLIN.child(Name.identifier("String")), Name.identifier("length"))

        private val NUMERIC_RECEIVERS: Set<FqName> = listOf("Int", "Long", "Float", "Double", "Byte", "Short").map { KOTLIN.child(Name.identifier(it)) }.toSet()
        private val ARITHMETIC_RECEIVERS: Set<FqName> = NUMERIC_RECEIVERS + KOTLIN.child(Name.identifier("String"))
        private val MEMBER_PACKAGES: Set<FqName> = setOf(FqName("kotlin.text"), COLLECTIONS)

        private val ARITHMETIC: Map<String, Cond.ArithOp> = mapOf(
            "plus" to Cond.ArithOp.PLUS,
            "minus" to Cond.ArithOp.MINUS,
            "times" to Cond.ArithOp.TIMES,
            "div" to Cond.ArithOp.DIV,
            "rem" to Cond.ArithOp.REM,
        )
        private val CONVERSIONS: Map<String, Cond.NumericKind> = mapOf(
            "toInt" to Cond.NumericKind.INT,
            "toLong" to Cond.NumericKind.LONG,
            "toFloat" to Cond.NumericKind.FLOAT,
            "toDouble" to Cond.NumericKind.DOUBLE,
        )
    }
}

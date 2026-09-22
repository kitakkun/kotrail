package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.FixEdit
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirBasicExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirBooleanOperatorExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirEqualityOperatorCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirQualifiedAccessExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirWhenExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirComparisonExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.contracts.description.LogicOperationKind
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.scopes.getFunctions
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isNullLiteral
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.text
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.types.ConstantValueKind

/**
 * The idioms the prefer-idiom rule asks for. Each has a key that `preferIdiom.disabled` accepts,
 * and every checker below names the one it found.
 */
internal object Idioms {
    const val EMPTINESS = "emptiness"
    const val NEGATION = "negation"
    const val NULL_OR_EMPTY = "nullOrEmpty"
    const val CHAIN = "chain"
    const val ELVIS = "elvis"

    val ALL = listOf(EMPTINESS, NEGATION, NULL_OR_EMPTY, CHAIN, ELVIS)
}

/**
 * The shared shape of every idiom checker: a receiver of a known kind (a collection, a map, an
 * array, a character sequence, or an iterable pipeline), matched by type rather than by name,
 * which is what tells `list.size == 0` from a `size` that happens to be an `Int` on some class.
 */
private object IdiomSupport {
    private val sizedTypes: List<ClassId> = listOf(StandardClassIds.Collection, StandardClassIds.Map, StandardClassIds.Array, StandardClassIds.CharSequence)
    private val pipelineTypes: List<ClassId> = listOf(StandardClassIds.Iterable, StandardClassIds.Array, ClassId(FqName("kotlin.sequences"), Name.identifier("Sequence")))

    context(context: CheckerContext)
    fun enabled(idiom: String): Boolean {
        val config = context.session.kotrailConfig
        return config.isEnabled(KotrailRule.PREFER_IDIOM) && idiom !in config.preferIdiom.disabled
    }

    fun ConeKotlinType.isSized(session: FirSession): Boolean = sizedTypes.any { isSubtypeOf(it, session) }

    fun ConeKotlinType.isPipeline(session: FirSession): Boolean = pipelineTypes.any { isSubtypeOf(it, session) }

    private fun ConeKotlinType.isSubtypeOf(classId: ClassId, session: FirSession): Boolean {
        val expanded = fullyExpandedType(session)
        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) ?: return false
        // Nullable, so that a smart-cast `String?` counts too; the idiom is asked for on the value as written.
        val superType = classId.constructClassLikeType(Array(symbol.typeParameterSymbols.size) { ConeStarProjection }, isMarkedNullable = true)
        return AbstractTypeChecker.isSubtypeOf(session.typeContext, expanded, superType)
    }

    fun FirExpression.unwrap(): FirExpression = if (this is FirSmartCastExpression) originalExpression.unwrap() else this

    /** `receiver.` for the replacement, or nothing when the receiver is implicit. */
    fun receiverPrefix(receiver: FirExpression?): String? {
        if (receiver == null) return ""
        val written = receiver.source?.text?.toString() ?: return null
        return "$written."
    }

    private val integerKinds = setOf(ConstantValueKind.Int, ConstantValueKind.Long, ConstantValueKind.IntegerLiteral, ConstantValueKind.Short, ConstantValueKind.Byte)

    private fun FirExpression.integerValue(): Long? =
        (this as? FirLiteralExpression)?.takeIf { it.kind in integerKinds }?.value?.let { (it as? Number)?.toLong() }

    fun FirExpression.isZero(): Boolean = integerValue() == 0L

    fun FirExpression.isOne(): Boolean = integerValue() == 1L

    context(context: CheckerContext, reporter: DiagnosticReporter)
    fun report(source: KtSourceElement, idiom: String, written: String, replacement: String) {
        reportKotrail(
            source,
            KotrailDiagnostics.PREFER_IDIOM,
            written,
            "$replacement ($idiom)",
            listOf(FixEdit(source.startOffset, source.endOffset, replacement)),
        )
    }
}

/** `x.size == 0`, `x.count() == 0`, `x.length != 0` → `x.isEmpty()` / `x.isNotEmpty()`. */
object EmptinessIdiomChecker : FirEqualityOperatorCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirEqualityOperatorCall) {
        if (!IdiomSupport.enabled(Idioms.EMPTINESS)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val equal = when (expression.operation) {
            FirOperation.EQ -> true
            FirOperation.NOT_EQ -> false
            else -> return
        }
        val (measure, zero) = expression.arguments.map { it.unwrapArgument().let { a -> with(IdiomSupport) { a.unwrap() } } }.let { args ->
            if (args.size != 2) return
            when {
                with(IdiomSupport) { args[1].isZero() } -> args[0] to args[1]
                with(IdiomSupport) { args[0].isZero() } -> args[1] to args[0]
                else -> return
            }
        }
        val prefix = measure.sizePrefix(context.session) ?: return
        val replacement = prefix + if (equal) "isEmpty()" else "isNotEmpty()"
        IdiomSupport.report(source, Idioms.EMPTINESS, source.text.toString(), replacement)
    }
}

/** `x.size > 0`, `x.size >= 1` → `x.isNotEmpty()`; `x.size < 1` → `x.isEmpty()`. */
object SizeComparisonIdiomChecker : FirBasicExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirStatement) {
        if (expression !is FirComparisonExpression) return
        if (!IdiomSupport.enabled(Idioms.EMPTINESS)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val call = expression.compareToCall
        val left = call.explicitReceiver?.let { with(IdiomSupport) { it.unwrap() } } ?: return
        val right = call.arguments.singleOrNull()?.unwrapArgument()?.let { with(IdiomSupport) { it.unwrap() } } ?: return
        val notEmpty = with(IdiomSupport) {
            when {
                expression.operation == FirOperation.GT && right.isZero() -> true
                expression.operation == FirOperation.GT_EQ && right.isOne() -> true
                expression.operation == FirOperation.LT && right.isOne() -> false
                expression.operation == FirOperation.LT_EQ && right.isZero() -> false
                else -> return
            }
        }
        val prefix = left.sizePrefix(context.session) ?: return
        val replacement = prefix + if (notEmpty) "isNotEmpty()" else "isEmpty()"
        IdiomSupport.report(source, Idioms.EMPTINESS, source.text.toString(), replacement)
    }
}

private fun FirCallableSymbol<*>.fqn(): String? = callableId?.asSingleFqName()?.asString()

/**
 * Whether a configured replacement names a function that exists: a top-level function of the
 * package before the last dot, or a member of the class before it. A replacement that resolves
 * to nothing is never asked for, so a typo in the configuration stays silent rather than
 * producing a rewrite that does not compile.
 */
private fun FirSession.functionExists(fqn: String): Boolean {
    val name = Name.identifier(fqn.substringAfterLast('.'))
    val owner = FqName(fqn.substringBeforeLast('.'))
    if (symbolProvider.getTopLevelFunctionSymbols(owner, name).isNotEmpty()) return true
    val classSymbol = symbolProvider.getClassLikeSymbolByClassId(ClassId.topLevel(owner)) as? FirClassSymbol<*>
        ?: owner.parent().takeUnless { it.isRoot }?.let { parent ->
            symbolProvider.getClassLikeSymbolByClassId(ClassId(parent, owner.shortName())) as? FirClassSymbol<*>
        }
        ?: return false
    return classSymbol.unsubstitutedScope(this, ScopeSession(), withForcedTypeCalculator = false, memberRequiredPhase = null).getFunctions(name).isNotEmpty()
}

/** The `receiver.` of a `size` / `length` read or a `count()` call on a sized type, or `null` when the expression is not one. */
private fun FirExpression.sizePrefix(session: FirSession): String? {
    val (name, receiver) = when (this) {
        is FirPropertyAccessExpression -> (calleeReference.toResolvedCallableSymbol()?.name?.asString() ?: return null) to explicitReceiver
        is FirFunctionCall -> {
            val callee = calleeReference.toResolvedNamedFunctionSymbol() ?: return null
            if (callee.valueParameterSymbols.isNotEmpty() || arguments.isNotEmpty()) return null
            callee.name.asString() to explicitReceiver
        }
        else -> return null
    }
    if (name != "size" && name != "length" && name != "count") return null
    val receiverType = receiver?.resolvedType ?: (this as? FirQualifiedAccessExpression)?.dispatchReceiver?.resolvedType ?: return null
    if (!with(IdiomSupport) { receiverType.isSized(session) }) return null
    return IdiomSupport.receiverPrefix(receiver)
}

/** `!x.isEmpty()` → `x.isNotEmpty()`, and the same for `isNotEmpty`, `isBlank`, `isNotBlank`. */
object NegationIdiomChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    private val flipped = mapOf("isEmpty" to "isNotEmpty", "isNotEmpty" to "isEmpty", "isBlank" to "isNotBlank", "isNotBlank" to "isBlank")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        if (!IdiomSupport.enabled(Idioms.NEGATION)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        if (callee.name.asString() != "not" || callee.callableId?.classId != StandardClassIds.Boolean) return
        val inner = expression.explicitReceiver?.let { with(IdiomSupport) { it.unwrap() } } as? FirFunctionCall ?: return
        val innerCallee = inner.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        val replacementName = flipped[innerCallee.name.asString()] ?: return
        if (inner.arguments.isNotEmpty()) return
        val receiverType = inner.explicitReceiver?.resolvedType ?: inner.dispatchReceiver?.resolvedType ?: return
        if (!with(IdiomSupport) { receiverType.isSized(context.session) }) return
        val prefix = IdiomSupport.receiverPrefix(inner.explicitReceiver) ?: return
        IdiomSupport.report(source, Idioms.NEGATION, source.text.toString(), "$prefix$replacementName()")
    }
}

/** `x == null || x.isEmpty()` → `x.isNullOrEmpty()`, and `isBlank` → `isNullOrBlank()`. */
object NullOrEmptyIdiomChecker : FirBooleanOperatorExpressionChecker(MppCheckerKind.Common) {
    private val merged = mapOf("isEmpty" to "isNullOrEmpty", "isBlank" to "isNullOrBlank")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirBooleanOperatorExpression) {
        if (!IdiomSupport.enabled(Idioms.NULL_OR_EMPTY)) return
        if (expression.kind != LogicOperationKind.OR) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val nullCheck = expression.leftOperand as? FirEqualityOperatorCall ?: return
        if (nullCheck.operation != FirOperation.EQ) return
        val checked = nullCheck.arguments.map { it.unwrapArgument() }.let { args ->
            if (args.size != 2) return
            when {
                args[1].isNullLiteral -> args[0]
                args[0].isNullLiteral -> args[1]
                else -> return
            }
        }.let { with(IdiomSupport) { it.unwrap() } } as? FirQualifiedAccessExpression ?: return
        val call = expression.rightOperand.let { with(IdiomSupport) { it.unwrap() } } as? FirFunctionCall ?: return
        val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        val replacementName = merged[callee.name.asString()] ?: return
        if (call.arguments.isNotEmpty()) return
        val receiver = call.explicitReceiver?.let { with(IdiomSupport) { it.unwrap() } } as? FirQualifiedAccessExpression ?: return
        if (receiver.calleeReference.toResolvedCallableSymbol() != checked.calleeReference.toResolvedCallableSymbol()) return
        if (receiver.explicitReceiver != null || checked.explicitReceiver != null) return
        if (!with(IdiomSupport) { receiver.resolvedType.isSized(context.session) }) return
        val written = checked.source?.text?.toString() ?: return
        IdiomSupport.report(source, Idioms.NULL_OR_EMPTY, source.text.toString(), "$written.$replacementName()")
    }
}

/**
 * `xs.filter { p }.first()` → `xs.first { p }`, and the rest of the family; `xs.map { f }.filterNotNull()`
 * → `xs.mapNotNull { f }`; `xs.filter { p }.size` → `xs.count { p }`.
 */
object ChainIdiomChecker : FirQualifiedAccessExpressionChecker(MppCheckerKind.Common) {
    private val afterFilter = mapOf(
        "first" to "first", "firstOrNull" to "firstOrNull", "last" to "last", "lastOrNull" to "lastOrNull",
        "single" to "single", "singleOrNull" to "singleOrNull", "any" to "any", "none" to "none",
        "count" to "count", "isNotEmpty" to "any", "isEmpty" to "none", "size" to "count",
    )

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirQualifiedAccessExpression) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.PREFER_IDIOM)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val outerSymbol = expression.calleeReference.toResolvedCallableSymbol() ?: return
        val outerName = outerSymbol.name.asString()
        if (expression is FirFunctionCall && expression.arguments.isNotEmpty()) return
        val inner = expression.explicitReceiver?.let { with(IdiomSupport) { it.unwrap() } } as? FirFunctionCall ?: return
        val innerSymbol = inner.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        val innerName = innerSymbol.name.asString()
        val argument = inner.arguments.singleOrNull()?.unwrapArgument() ?: return
        val configured = context.session.kotrailConfig.preferIdiom.chains.firstOrNull {
            it.inner == innerSymbol.fqn() && it.outer == outerSymbol.fqn()
        }
        if (configured != null && !context.session.functionExists(configured.replacement)) return
        val replacementName = configured?.replacement?.substringAfterLast('.') ?: run {
            if (!IdiomSupport.enabled(Idioms.CHAIN)) return
            val receiverType = inner.explicitReceiver?.resolvedType ?: return
            if (!with(IdiomSupport) { receiverType.isPipeline(context.session) }) return
            when {
                innerName == "filter" -> afterFilter[outerName] ?: return
                innerName == "map" && outerName == "filterNotNull" -> "mapNotNull"
                else -> return
            }
        }
        val prefix = IdiomSupport.receiverPrefix(inner.explicitReceiver) ?: return
        val argumentText = argument.source?.text?.toString() ?: return
        val call = if (argument is FirAnonymousFunctionExpression) "$replacementName $argumentText" else "$replacementName($argumentText)"
        IdiomSupport.report(source, Idioms.CHAIN, source.text.toString(), "$prefix$call")
    }
}

/** The project's own call idioms: `fqn(literal)` → `name()`, from `preferIdiom.calls`. */
object ConfiguredCallIdiomChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.PREFER_IDIOM) || config.preferIdiom.calls.isEmpty()) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        val fqn = callee.fqn()
        val argument = expression.arguments.singleOrNull()?.unwrapArgument() ?: return
        val written = argument.source?.text?.toString()?.trim() ?: return
        val idiom = config.preferIdiom.calls.firstOrNull { it.fqn == fqn && it.literal == written } ?: return
        if (!context.session.functionExists(idiom.replacement)) return
        val prefix = IdiomSupport.receiverPrefix(expression.explicitReceiver) ?: return
        IdiomSupport.report(source, "calls", source.text.toString(), "$prefix${idiom.replacement.substringAfterLast('.')}()")
    }
}

/** `if (x != null) x else y` → `x ?: y`. */
object ElvisIdiomChecker : FirWhenExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirWhenExpression) {
        if (!IdiomSupport.enabled(Idioms.ELVIS)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (expression.subjectVariable != null || expression.branches.size != 2) return
        val (thenBranch, elseBranch) = expression.branches
        if (elseBranch.condition !is FirElseIfTrueCondition) return
        val condition = thenBranch.condition as? FirEqualityOperatorCall ?: return
        if (condition.operation != FirOperation.NOT_EQ) return
        val checked = condition.arguments.map { it.unwrapArgument() }.let { args ->
            if (args.size != 2) return
            when {
                args[1].isNullLiteral -> args[0]
                args[0].isNullLiteral -> args[1]
                else -> return
            }
        }.let { with(IdiomSupport) { it.unwrap() } } as? FirQualifiedAccessExpression ?: return
        if (checked.explicitReceiver != null) return
        val returned = thenBranch.result.statements.singleOrNull()?.let { with(IdiomSupport) { (it as? FirExpression)?.unwrap() } } as? FirQualifiedAccessExpression ?: return
        if (returned.explicitReceiver != null) return
        if (returned.calleeReference.toResolvedCallableSymbol() != checked.calleeReference.toResolvedCallableSymbol()) return
        val fallback = elseBranch.result.statements.singleOrNull() as? FirExpression ?: return
        val written = checked.source?.text?.toString() ?: return
        val fallbackText = fallback.source?.text?.toString() ?: return
        // Elvis binds tighter than the boolean operators and looser than the rest; parenthesize what could rebind.
        val safeFallback = if (fallback is FirQualifiedAccessExpression || fallback is FirLiteralExpression || fallback is FirFunctionCall) fallbackText else "($fallbackText)"
        IdiomSupport.report(source, Idioms.ELVIS, source.text.toString(), "$written ?: $safeFallback")
    }
}

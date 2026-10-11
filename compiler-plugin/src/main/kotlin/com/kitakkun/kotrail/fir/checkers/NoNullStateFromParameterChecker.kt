package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.exclude.Glob
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.isValueUsed
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.test.isTestSourcePath
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.contracts.description.LogicOperationKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertyGetter
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertySetter
import org.jetbrains.kotlin.fir.declarations.utils.correspondingValueParameterFromPrimaryConstructor
import org.jetbrains.kotlin.fir.declarations.utils.isActual
import org.jetbrains.kotlin.fir.declarations.utils.isData
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isInlineOrValue
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirArgumentList
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirCheckNotNullCall
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenBranch
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.expressions.unwrapSmartcastExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.references.toResolvedPropertySymbol
import org.jetbrains.kotlin.fir.references.toResolvedValueParameterSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.canBeNull
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.isNothing
import org.jetbrains.kotlin.fir.types.isNullLiteral
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Reports a null that a constructor receives and the object carries as state, when every use of
 * that state only bails out on null:
 *
 * ```kotlin
 * class Recorder(ffmpegPath: String?) {
 *     private val ffmpegPath: String? = ffmpegPath          // reported
 *     fun record() { val path = ffmpegPath ?: return; run(path) }
 *     fun probe(): Int = ffmpegPath!!.length
 * }
 *
 * class Recorder(private val ffmpegPath: String?) {       // reported
 *     fun record() { ffmpegPath?.let { run(it) } }
 * }
 * ```
 *
 * Whether there is a value is the caller's decision, made once; carried inside, it is made again
 * at every use, where each `?: return` silently does nothing and each `!!` fails late.
 *
 * The subject is a private property with a nullable type and default accessors, declared in the
 * primary constructor or initialized with a plain read of a nullable primary-constructor
 * parameter, in a class that is not a data, value, serializable or parcelable class, and not
 * `expect` or `actual`. Being private, every read of it is in the class, and every read must
 * bail out ([Use.BAIL_OUT]); a single read that uses the null as a value ([Use.VALUE]) keeps the
 * property quiet, and so does a read of the constructor parameter elsewhere in the class that
 * uses it as a value. A `var` stays quiet when the class assigns it anything but a read of a
 * nullable parameter: `x = null` makes null a state the class manages. Overrides, test source
 * sets and properties nothing reads are left alone.
 */
object NoNullStateFromParameterChecker : FirPropertyChecker(MppCheckerKind.Common) {
    /** Annotations, by simple name, of classes whose properties mirror data that is read and written elsewhere. */
    private val MIRRORING_ANNOTATIONS = setOf("Serializable", "Parcelize")
    private val NOT_NULL_FUNCTIONS = setOf("kotlin.requireNotNull", "kotlin.checkNotNull")
    private val CONDITION_FUNCTIONS = setOf("kotlin.require", "kotlin.check")
    private const val BOOLEAN_NOT = "kotlin.Boolean.not"

    /** What a read does with a null. */
    private enum class Use {
        /** Leaves on null, or does nothing: `?: return`, `!!`, `requireNotNull`, a guard that exits, a dropped safe call. */
        BAIL_OUT,

        /** Treats the null as a value: a default, a stored or passed nullable, a null test whose answer is used. */
        VALUE,
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        val session = context.session
        if (!session.kotrailConfig.isEnabled(KotrailRule.NO_NULL_STATE_FROM_PARAMETER)) return
        val source = declaration.source ?: return
        val declaredInConstructor = source.kind == KtFakeSourceElementKind.PropertyFromParameter
        if (source.kind is KtFakeSourceElementKind && !declaredInConstructor) return
        if (!declaration.origin.fromSource) return
        val owner = context.containingDeclarations.lastOrNull() as? FirRegularClassSymbol ?: return
        if (!owner.holdsOwnState()) return
        if (!declaration.isNullablePrivateState(session)) return
        val parameter = storedParameter(declaration) ?: return
        if (!parameter.resolvedReturnType.isNullable(session)) return
        val path = context.containingFileSymbol?.sourceFile?.path
        if (path != null && isTestSourcePath(path)) return
        if (!onlyBailsOut(owner, declaration, parameter, session)) return

        val site = if (declaredInConstructor) parameter.source else source
        reportKotrail(site, KotrailDiagnostics.NULL_STORED_FROM_PARAMETER, declaration.name.asString(), parameter.name.asString())
    }

    /** A class or enum class whose properties are its own state, not a mirror of data defined elsewhere. */
    private fun FirRegularClassSymbol.holdsOwnState(): Boolean {
        if (classKind != ClassKind.CLASS && classKind != ClassKind.ENUM_CLASS) return false
        if (isData || isInlineOrValue || isExpect || isActual) return false
        return resolvedAnnotationClassIds.none { it.shortClassName.asString() in MIRRORING_ANNOTATIONS }
    }

    private fun FirProperty.isNullablePrivateState(session: FirSession): Boolean {
        if (isLocal || isOverride || isExpect || isActual) return false
        if (!Visibilities.isPrivate(visibility)) return false
        if (delegate != null || receiverParameter != null) return false
        if (getter != null && getter !is FirDefaultPropertyGetter) return false
        if (setter != null && setter !is FirDefaultPropertySetter) return false
        return returnTypeRef.coneType.isNullable(session)
    }

    /** Nullable as written, through type aliases; a bare type parameter is left to its type argument. */
    private fun ConeKotlinType.isNullable(session: FirSession): Boolean = fullyExpandedType(session).isMarkedNullable

    /**
     * The primary-constructor parameter [property] stores: the parameter it is declared as, or the
     * one its initializer reads as is.
     */
    private fun storedParameter(property: FirProperty): FirValueParameterSymbol? {
        property.correspondingValueParameterFromPrimaryConstructor?.let { return it }
        val read = property.initializer?.unwrapSmartcastExpression() as? FirPropertyAccessExpression ?: return null
        if (read.explicitReceiver != null) return null
        val parameter = read.calleeReference.toResolvedValueParameterSymbol() ?: return null
        val constructor = parameter.containingDeclarationSymbol as? FirConstructorSymbol ?: return null
        return parameter.takeIf { constructor.isPrimary }
    }

    @OptIn(SymbolInternals::class)
    private fun onlyBailsOut(
        owner: FirRegularClassSymbol,
        property: FirProperty,
        parameter: FirValueParameterSymbol,
        session: FirSession,
    ): Boolean {
        val loggers = session.kotrailConfig.catchTooBroad.loggers.map(::Glob)
        val finder = UseFinder(property, parameter, loggers, session)
        owner.fir.accept(finder)
        return !finder.keepsNull && finder.reads > 0
    }

    /**
     * Walks the class for the reads of [property] and of [parameter] outside [property] itself,
     * and for the assignments to [property]. [keepsNull] turns true on the first read that uses
     * the null as a value or the first assignment of anything but a nullable parameter. A branch
     * may log through [loggers] on its way out and still only bail out.
     */
    private class UseFinder(
        private val property: FirProperty,
        private val parameter: FirValueParameterSymbol,
        private val loggers: List<Glob>,
        private val session: FirSession,
    ) : FirVisitorVoid() {
        var reads = 0
            private set
        var keepsNull = false
            private set

        private val parents = mutableListOf<FirElement>()

        private val assignedTargets: MutableSet<FirExpression> = Collections.newSetFromMap(IdentityHashMap())

        override fun visitElement(element: FirElement) {
            if (keepsNull || element === property) return
            when (element) {
                is FirVariableAssignment -> if (element.assigns(property.symbol)) {
                    if (!element.rValue.isNullableParameterRead()) keepsNull = true
                }
                is FirQualifiedAccessExpression -> if (element !in assignedTargets) {
                    if (element.calleeReference.toResolvedPropertySymbol() == property.symbol) {
                        reads++
                        if (useOf(element) == Use.VALUE) keepsNull = true
                    } else if (element.calleeReference.toResolvedValueParameterSymbol() == parameter) {
                        if (useOf(element) == Use.VALUE) keepsNull = true
                    }
                }
            }
            if (keepsNull) return
            parents += element
            element.acceptChildren(this)
            parents.removeAt(parents.lastIndex)
        }

        private fun FirVariableAssignment.assigns(target: FirPropertySymbol): Boolean {
            // `x += 1` and `x++` assign through a reference to the original access.
            val access = (lValue as? FirDesugaredAssignmentValueReferenceExpression)?.expressionRef?.value ?: lValue
            assignedTargets += lValue
            assignedTargets += access
            return (access as? FirQualifiedAccessExpression)?.calleeReference?.toResolvedPropertySymbol() == target
        }

        private fun FirExpression.isNullableParameterRead(): Boolean {
            val read = unwrapSmartcastExpression() as? FirPropertyAccessExpression ?: return false
            if (read.explicitReceiver != null) return false
            val symbol = read.calleeReference.toResolvedValueParameterSymbol() ?: return false
            return symbol.resolvedReturnType.fullyExpandedType(session).isMarkedNullable
        }

        /**
         * What [read], whose parents are [parents], does with a null. A smart cast to a non-null
         * type means an earlier check already handled the null, and that check is judged on its
         * own; anything not recognized as a bail-out is a use as a value.
         */
        private fun useOf(read: FirQualifiedAccessExpression): Use {
            if (read !is FirPropertyAccessExpression) return Use.VALUE
            var child: FirElement = read
            var index = parents.lastIndex
            val smartCast = parents.getOrNull(index)
            if (smartCast is FirSmartCastExpression && smartCast.originalExpression === read) {
                if (smartCast.isStable && !smartCast.smartcastType.coneType.canBeNull(session)) return Use.BAIL_OUT
                child = smartCast
                index--
            }
            val at = skipArguments(index)
            return when (val parent = parents.getOrNull(at)) {
                is FirCheckNotNullCall -> Use.BAIL_OUT
                is FirElvisExpression -> if (parent.lhs === child && parent.rhs.exits()) Use.BAIL_OUT else Use.VALUE
                is FirSafeCallExpression -> if (parent.receiver === child) safeCallUse(at) else Use.VALUE
                is FirEqualityOperatorCall, is FirTypeOperatorCall -> nullCheckUse(parent as FirExpression, child, at)
                is FirFunctionCall -> {
                    val asserts = parent.calleeName() in NOT_NULL_FUNCTIONS && parent.arguments.firstOrNull()?.unwrapArgument() === child
                    if (asserts) Use.BAIL_OUT else Use.VALUE
                }
                else -> Use.VALUE
            }
        }

        /**
         * A safe call on the read, followed through the safe calls chained on it: `x?.let { }` and
         * `x?.a?.b()` do nothing on null when their value is dropped, and leave when it meets an
         * elvis that exits or a `!!`. A value that goes anywhere else carries the null on.
         */
        private fun safeCallUse(index: Int): Use {
            var at = index
            while (true) {
                val outer = parents.getOrNull(at - 1)
                if (outer is FirSafeCallExpression && outer.receiver === parents[at]) at-- else break
            }
            val chain = parents[at]
            return when (val consumer = parents.getOrNull(at - 1)) {
                is FirElvisExpression -> if (consumer.lhs === chain && consumer.rhs.exits()) Use.BAIL_OUT else Use.VALUE
                is FirCheckNotNullCall -> Use.BAIL_OUT
                else -> if (isValueUsed(parents.subList(0, at + 1))) Use.VALUE else Use.BAIL_OUT
            }
        }

        /**
         * A null test on the read (`x == null`, `x != null`, `x is T`), as the condition of an
         * `if` or `when` branch, through `!`, `&&` and `||` as long as a null still settles the
         * condition: the null leaves when the branch it selects exits, or when it selects no
         * branch at all. As the condition of `require` or `check`, a null that fails it leaves
         * too. Any other test is a use of the null as a value.
         */
        private fun nullCheckUse(check: FirExpression, operand: FirElement, index: Int): Use {
            var nullSelects = nullOutcome(check, operand) ?: return Use.VALUE
            var child: FirElement = check
            var at = skipArguments(index - 1)
            while (true) {
                when (val parent = parents.getOrNull(at) ?: return Use.VALUE) {
                    is FirBooleanOperatorExpression -> {
                        val settled = if (parent.kind == LogicOperationKind.OR) nullSelects else !nullSelects
                        if (!settled) return Use.VALUE
                    }
                    is FirFunctionCall -> when {
                        parent.calleeName() == BOOLEAN_NOT && parent.explicitReceiver === child -> nullSelects = !nullSelects
                        parent.calleeName() in CONDITION_FUNCTIONS && parent.arguments.firstOrNull()?.unwrapArgument() === child ->
                            return if (nullSelects) Use.VALUE else Use.BAIL_OUT
                        else -> return Use.VALUE
                    }
                    is FirWhenBranch -> {
                        val whenExpression = parents.getOrNull(at - 1) as? FirWhenExpression ?: return Use.VALUE
                        return if (parent.condition === child) branchUse(whenExpression, parent, nullSelects) else Use.VALUE
                    }
                    else -> return Use.VALUE
                }
                child = parents[at]
                at = skipArguments(at - 1)
            }
        }

        /** The value [check] takes when [operand] is null, or `null` when it is not a null test of [operand]. */
        private fun nullOutcome(check: FirExpression, operand: FirElement): Boolean? = when (check) {
            is FirEqualityOperatorCall -> {
                val arguments = check.arguments.map { it.unwrapArgument() }
                val other = when {
                    arguments.size != 2 -> null
                    arguments[0] === operand -> arguments[1]
                    arguments[1] === operand -> arguments[0]
                    else -> null
                }
                when {
                    other == null || !other.isNullLiteral -> null
                    check.operation == FirOperation.EQ || check.operation == FirOperation.IDENTITY -> true
                    check.operation == FirOperation.NOT_EQ || check.operation == FirOperation.NOT_IDENTITY -> false
                    else -> null
                }
            }
            is FirTypeOperatorCall -> {
                val nullIsInstance = check.conversionTypeRef.coneType.fullyExpandedType(session).isMarkedNullable
                when (check.operation) {
                    FirOperation.IS -> nullIsInstance
                    FirOperation.NOT_IS -> !nullIsInstance
                    else -> null
                }
            }
            else -> null
        }

        /**
         * Whether a null leaves through [whenExpression] when it settles [branch]'s condition to
         * [nullSelects]: the branch it selects only exits, or, with the condition false, nothing
         * follows the branch or only an `else` that only exits.
         */
        private fun branchUse(whenExpression: FirWhenExpression, branch: FirWhenBranch, nullSelects: Boolean): Use {
            if (nullSelects) return if (branch.result.onlyExits()) Use.BAIL_OUT else Use.VALUE
            val following = whenExpression.branches.dropWhile { it !== branch }.drop(1)
            val otherwise = following.singleOrNull()
            return when {
                following.isEmpty() -> Use.BAIL_OUT
                otherwise != null && otherwise.condition is FirElseIfTrueCondition && otherwise.result.onlyExits() -> Use.BAIL_OUT
                else -> Use.VALUE
            }
        }

        /**
         * A branch that exits and does nothing on the way but log: `{ return }`,
         * `{ log.warning("no path"); return }`. A branch that does other work before it exits,
         * `{ copyAsText(); return }`, is another way of doing the job, not a bail-out.
         */
        private fun FirBlock.onlyExits(): Boolean {
            if (!exits()) return false
            return statements.dropLast(1).all { statement -> statement is FirFunctionCall && statement.isLogging() }
        }

        private fun FirFunctionCall.isLogging(): Boolean {
            val callee = calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString() ?: return false
            return loggers.any { it.matches(callee) }
        }

        /** The nearest index at or below [index] that is not an argument list or an argument wrapper. */
        private fun skipArguments(index: Int): Int {
            var at = index
            while (at >= 0 && (parents[at] is FirArgumentList || parents[at] is FirWrappedArgumentExpression)) at--
            return at
        }

        /** `return`, `throw`, `break`, `continue`, or a call that returns `Nothing`, such as `error()` or `TODO()`. */
        private fun FirExpression.exits(): Boolean = resolvedType.isNothing

        private fun FirFunctionCall.calleeName(): String? =
            calleeReference.toResolvedNamedFunctionSymbol()?.callableId?.asSingleFqName()?.asString()
    }
}

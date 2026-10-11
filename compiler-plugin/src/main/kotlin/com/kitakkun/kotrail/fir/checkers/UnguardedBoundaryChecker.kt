@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.exclude.Glob
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.errors.FailureBoundary
import com.kitakkun.kotrail.fir.errors.caughtTypeNames
import com.kitakkun.kotrail.fir.errors.failureBoundary
import com.kitakkun.kotrail.fir.errors.rethrows
import com.kitakkun.kotrail.fir.isOfThisModule
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirTryExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.InlineStatus
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.isData
import org.jetbrains.kotlin.fir.declarations.utils.isInlineOrValue
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirCall
import org.jetbrains.kotlin.fir.expressions.FirCatch
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isNothing
import org.jetbrains.kotlin.fir.types.isNullableNothing
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.unwrapFakeOverrides
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.DataClassResolver
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.util.OperatorNameConventions

/**
 * Reports a `try` at a failure boundary whose catch clauses all name narrow types, around a call
 * into code the module does not declare:
 *
 * ```kotlin
 * val requests = Channel<Request>(UNLIMITED).also {
 *     scope.launch {
 *         for (request in it) {
 *             try {
 *                 client.send(request)
 *             } catch (e: IOException) {      // reported: anything else ends the loop for good
 *                 log.warn("send failed", e)
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * At a boundary ([failureBoundary]) nothing above the try receives a failure: an exception the
 * clauses do not name ends the coroutine, the consumer loop, the listener or the thread, and the
 * work stops without a trace. Narrow catches belong inside the logic; at the boundary a broad
 * clause that logs or reports is what keeps the work alive (catchTooBroad accepts it there).
 *
 * Narrow is none of `catchTooBroad.types`. The try body must run code the project does not
 * declare, outside nested non-inline lambdas: call a function declared outside the module (other
 * than in `kotlin` and `kotlinx.coroutines`), or invoke a function-typed value (`block()`), whose
 * code is unknown here. A project's own code is presumed to throw what the project knows about,
 * so a call to one of the module's own functions counts only when the function reaches such a
 * call: its body, in this compilation's sources, is searched the same way, and so are the bodies
 * of the own functions it calls, up to [OWN_CALL_DEPTH] functions deep, each followed once. The
 * message then names both (`'writeAndRecord' (which calls 'Messenger.request')`). An own function
 * loaded from a class file, as incremental compilation loads the files it does not compile again,
 * has no body here and is not followed. The constructor of a data class or a value class declared
 * outside does not count: it only holds the values it is given
 * (`writeAndRecord(SetViewAttribute(...))` counts for what `writeAndRecord` calls, not for the
 * constructor). Nor do the members the compiler generates for a data class, `copy` and
 * `componentN` among them, which only read or copy those values.
 * Quiet: a try whose every clause rethrows (cleanup, translation), a try nested in the try block
 * of a try with a broad clause at the same root, a launcher whose receiver or arguments carry a
 * `CoroutineExceptionHandler` (`scope.launch(handler)`, `launch(Dispatchers.IO + handler)`) or
 * name a scope listed in `guardedScopes`, and a flow chain with a `.catch { }` downstream of the
 * try's lambda.
 */
object UnguardedBoundaryChecker : FirTryExpressionChecker(MppCheckerKind.Common) {
    private val EXCEPTION_HANDLER = ClassId.topLevel(FqName("kotlinx.coroutines.CoroutineExceptionHandler"))
    private const val FLOW_CATCH = "kotlinx.coroutines.flow.catch"

    /** Packages whose functions are not counted as calls out: the standard library and the coroutines machinery. */
    private val QUIET_PACKAGES = listOf("kotlin", "kotlinx.coroutines")

    /** `getRuntime`, `isHidden`: the JavaBeans form of a getter. */
    private val GETTER_NAME = Regex("(get|is)\\p{Lu}.*")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirTryExpression) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.UNGUARDED_BOUNDARY)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val session = context.session
        val catches = expression.catches
        val first = catches.firstOrNull() ?: return
        val broad = config.catchTooBroad.types
        if (catches.any { it.isBroad(broad, session) }) return
        if (catches.all { it.rethrows() }) return
        val boundary = failureBoundary(config.failureBoundaries) ?: return
        if (boundary.enclosingTries.any { enclosing -> enclosing.catches.any { it.isBroad(broad, session) } }) return
        if (boundary.downstream.any { it.calleeReference.toResolvedCallableSymbol()?.fqName() == FLOW_CATCH }) return
        val launcher = boundary.launcher
        if (launcher != null && launcher.carriesExceptionHandler(session)) return
        val guardedScopes = config.failureBoundaries.guardedScopes.map(::Glob)
        if (launcher != null && launcher.launchesInGuardedScope(guardedScopes)) return
        val outside = expression.tryBlock.throwingCall(session, depth = 0, following = emptySet()) ?: return
        val reportAt = first.parameter.source ?: return

        val types = catches.joinToString(", ") { it.parameter.returnTypeRef.coneType.classId?.shortClassName?.asString() ?: "?" }
        val remedy = if (boundary.isCoroutine()) {
            "Catch Exception here as well and log or report it, or install a CoroutineExceptionHandler where the scope is created"
        } else {
            "Catch Exception as well and log or report it"
        }
        // Without a handler, the failure reaches the thread's default handler, which logs it or, on
        // Android, ends the process.
        val rest = "from ${outside.described} ends '${boundary.description}' here, and only the default handler sees it. $remedy"
        reportKotrail(reportAt, KotrailDiagnostics.BOUNDARY_UNGUARDED, types, rest)
    }

    private fun FirCatch.isBroad(broad: List<String>, session: FirSession): Boolean = caughtTypeNames(session).any { it in broad }

    private fun FirCallableSymbol<*>.fqName(): String? = callableId?.asSingleFqName()?.asString()

    /** Whether the root is started by a coroutine launcher, whose scope can take a CoroutineExceptionHandler. */
    private fun FailureBoundary.isCoroutine(): Boolean {
        val callee = (launcher as? FirQualifiedAccessExpression)?.calleeReference?.toResolvedCallableSymbol() ?: return false
        val packageName = callee.callableId?.packageName?.asString() ?: return false
        return packageName == "kotlinx.coroutines" || packageName.startsWith("kotlinx.coroutines.")
    }

    /** Whether the launcher's receiver or one of its arguments, outside lambdas, is or contains a CoroutineExceptionHandler. */
    private fun FirCall.carriesExceptionHandler(session: FirSession): Boolean {
        val receiver = (this as? FirQualifiedAccessExpression)?.explicitReceiver
        return (listOfNotNull(receiver) + argumentList.arguments).any { it.mentionsExceptionHandler(session) }
    }

    private fun FirExpression.mentionsExceptionHandler(session: FirSession): Boolean {
        var found = false
        accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (found) return
                if (element is FirExpression && element.resolvedType.isSubtypeOf(EXCEPTION_HANDLER, session)) {
                    found = true
                    return
                }
                element.acceptChildren(this)
            }

            override fun visitAnonymousFunctionExpression(anonymousFunctionExpression: FirAnonymousFunctionExpression) {}
        })
        return found
    }

    /**
     * Whether the launcher runs in a scope that `guardedScopes` names: its receiver or one of its
     * arguments (`launchIn(pluginScope)`) reads a listed property or calls a listed function,
     * directly or through a local `val` initialized that way.
     */
    private fun FirCall.launchesInGuardedScope(guardedScopes: List<Glob>): Boolean {
        if (guardedScopes.isEmpty()) return false
        val receiver = (this as? FirQualifiedAccessExpression)?.explicitReceiver
        val candidates = listOfNotNull(receiver) + argumentList.arguments.map { it.unwrapArgument() }
        return candidates.any { it.isGuardedScope(guardedScopes, throughLocal = true) }
    }

    private fun FirExpression.isGuardedScope(guardedScopes: List<Glob>, throughLocal: Boolean): Boolean {
        val unwrapped = (this as? FirSmartCastExpression)?.originalExpression ?: this
        val access = unwrapped as? FirQualifiedAccessExpression ?: return false
        val symbol = access.calleeReference.toResolvedCallableSymbol() ?: return false
        if (symbol is FirPropertySymbol && symbol.isLocal) {
            if (!throughLocal || !symbol.isVal) return false
            return symbol.resolvedInitializer?.isGuardedScope(guardedScopes, throughLocal = false) == true
        }
        val names = listOfNotNull(symbol.callableId, symbol.unwrapFakeOverrides().callableId).map { it.asSingleFqName().asString() }
        return names.any { name -> guardedScopes.any { it.matches(name) } }
    }

    /** How many of the module's own functions deep a call is followed to a call out: the try calls the first. */
    private const val OWN_CALL_DEPTH = 3

    /**
     * A call in a try block that runs code the project does not declare, at [offset] in the file.
     * [outside]: how the message names that code. [via]: the module's own function the block
     * calls to reach it, or `null` when the block calls it itself. [preferred] is false for a
     * constructor and a getter, which the message names only when the block calls nothing else.
     */
    private class ThrowingCall(val offset: Int, val outside: String, val via: String?, val preferred: Boolean) {
        /** `'OutputStream.write'`, or `'writeAndRecord' (which calls 'OutputStream.write')`. */
        val described: String
            get() = if (via == null) "'$outside'" else "'$via' (which calls '$outside')"
    }

    /**
     * How the message names the call in the block that can throw: the first that invokes a
     * function-typed value (`capture(...)`) or calls a function the module does not declare
     * (`Image.makeFromEncoded`), outside the standard library and kotlinx.coroutines; a constructor
     * or a getter only when nothing else qualifies. First is by where the callee's name starts, so
     * a call comes before the calls in its arguments (`send(Request(...))` names `send`) and after
     * the calls in its receiver (`Image.makeFromEncoded(png).close()` names `makeFromEncoded`).
     * `null` when the block calls none of them. Calls in a nested non-inline lambda, a local
     * function or a local class run later, outside this try, and do not count. A call to one of
     * the module's own functions counts as the call out its body reaches, chosen the same way;
     * [depth] is how many own functions deep this block is, and [following] the own functions
     * whose bodies are being searched, which a recursive call does not enter again.
     */
    private fun FirBlock.throwingCall(session: FirSession, depth: Int, following: Set<FirNamedFunctionSymbol>): ThrowingCall? {
        val calls = mutableListOf<ThrowingCall>()
        accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                // Checked here for every kind of call: FirVisitorVoid routes an implicit invoke
                // past visitFunctionCall.
                if (element is FirFunctionCall) element.throwingCall(session, depth, following)?.let(calls::add)
                element.acceptChildren(this)
            }

            override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
                if (anonymousFunction.inlineStatus == InlineStatus.Inline) visitElement(anonymousFunction)
            }

            override fun visitNamedFunction(namedFunction: FirNamedFunction) {}

            override fun visitRegularClass(regularClass: FirRegularClass) {}

            override fun visitAnonymousObject(anonymousObject: FirAnonymousObject) {}
        })
        return calls.filter { it.preferred }.minByOrNull { it.offset } ?: calls.minByOrNull { it.offset }
    }

    private fun FirFunctionCall.throwingCall(session: FirSession, depth: Int, following: Set<FirNamedFunctionSymbol>): ThrowingCall? {
        val callee = calleeReference.toResolvedCallableSymbol() ?: return null
        val offset = (calleeReference.source ?: source)?.startOffset ?: return null
        val function = (dispatchReceiver ?: explicitReceiver)?.takeIf { callee.name == OperatorNameConventions.INVOKE }
        if (function != null && function.resolvedType.fullyExpandedType(session).lowerBoundIfFlexible().isSomeFunctionType(session)) {
            val value = (function as? FirQualifiedAccessExpression)?.calleeReference?.toResolvedCallableSymbol()?.name
            return ThrowingCall(offset, "${value ?: OperatorNameConventions.INVOKE}(...)", via = null, preferred = true)
        }
        if (!callee.isCallOut(session)) return callee.reachedCallOut(offset, session, depth, following)
        if (callee.onlyHoldsValues(session)) return null
        return ThrowingCall(offset, callee.displayName(session), via = null, preferred = callee !is FirConstructorSymbol && !callee.isGetter())
    }

    /**
     * The call out this callee's body reaches when it is one of the module's own functions with
     * its body in this compilation's sources, as the call at [offset] that the message names
     * through it; `null` past [OWN_CALL_DEPTH], for a function already being searched, and for a
     * function loaded from a class file, which has no body here.
     */
    private fun FirCallableSymbol<*>.reachedCallOut(
        offset: Int,
        session: FirSession,
        depth: Int,
        following: Set<FirNamedFunctionSymbol>,
    ): ThrowingCall? {
        if (depth >= OWN_CALL_DEPTH) return null
        val own = unwrapFakeOverrides() as? FirNamedFunctionSymbol ?: return null
        if (own.origin != FirDeclarationOrigin.Source || own in following) return null
        val body = own.fir.body ?: return null
        val reached = body.throwingCall(session, depth + 1, following + own) ?: return null
        return ThrowingCall(offset, reached.outside, via = name.asString(), reached.preferred)
    }

    /**
     * A constructor of a data class or a value class, which only stores what it is given, or a
     * member the compiler generates for a data class, which only reads, compares or copies those
     * values: `copy`, `componentN`, and `equals`, `hashCode` and `toString` when the class does not
     * declare its own. A data class loaded from a class file does not say which of its members
     * were generated. Its `copy` counts when it takes the primary constructor's parameter types,
     * and its `componentN` when it takes nothing and N is at most their number, since the compiler
     * rejects a member written by hand with either signature; its `equals`, `hashCode` and
     * `toString` still count as calls out.
     */
    private fun FirCallableSymbol<*>.onlyHoldsValues(session: FirSession): Boolean {
        if (this is FirConstructorSymbol) {
            val owner = resolvedReturnType.toRegularClassSymbol(session) ?: return false
            return owner.isData || owner.isInlineOrValue
        }
        val member = unwrapFakeOverrides() as? FirNamedFunctionSymbol ?: return false
        val owner = member.dispatchReceiverType?.toRegularClassSymbol(session)?.takeIf { it.isData } ?: return false
        if (member.origin == FirDeclarationOrigin.Synthetic.DataClassMember) return true
        val properties = owner.primaryConstructorIfAny(session)?.valueParameterSymbols ?: return false
        val parameters = member.valueParameterSymbols
        return when {
            DataClassResolver.isCopy(member.name) -> parameters.map { it.resolvedReturnType } == properties.map { it.resolvedReturnType }
            DataClassResolver.isComponentLike(member.name) ->
                parameters.isEmpty() && member.receiverParameterSymbol == null &&
                    DataClassResolver.getComponentIndex(member.name.asString()) in 1..properties.size
            else -> false
        }
    }

    private fun FirCallableSymbol<*>.isCallOut(session: FirSession): Boolean {
        if (isOfThisModule(session) || origin == FirDeclarationOrigin.SamConstructor) return false
        val packageName = callableId?.packageName?.asString() ?: return false
        return QUIET_PACKAGES.none { packageName == it || packageName.startsWith("$it.") }
    }

    /** A getter written as a call, with no parameters: `Runtime.getRuntime()`, `file.isHidden()`. */
    private fun FirCallableSymbol<*>.isGetter(): Boolean =
        this is FirNamedFunctionSymbol && valueParameterSymbols.isEmpty() && GETTER_NAME.matches(name.asString())

    /**
     * `FileInputStream` for a constructor, `OutputStream.write` for a member, `Image.makeFromEncoded`
     * for a member of a companion object, `connect` for a top-level function.
     */
    private fun FirCallableSymbol<*>.displayName(session: FirSession): String {
        val id = callableId ?: return name.asString()
        val classId = id.classId
        val ownerId = if (classId != null && session.symbolProvider.getClassLikeSymbolByClassId(classId)?.isCompanion == true) {
            classId.outerClassId
        } else {
            classId
        }
        val owner = ownerId?.shortClassName?.asString()
        return when {
            this is FirConstructorSymbol -> owner ?: name.asString()
            owner != null -> "$owner.${id.callableName}"
            else -> id.callableName.asString()
        }
    }

    private fun ConeKotlinType.isSubtypeOf(classId: ClassId, session: FirSession): Boolean {
        val expanded = fullyExpandedType(session)
        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) ?: return false
        val superType = classId.constructClassLikeType(Array(symbol.typeParameterSymbols.size) { ConeStarProjection }, isMarkedNullable = true)
        return !expanded.isNothing && !expanded.isNullableNothing && AbstractTypeChecker.isSubtypeOf(session.typeContext, expanded, superType)
    }
}

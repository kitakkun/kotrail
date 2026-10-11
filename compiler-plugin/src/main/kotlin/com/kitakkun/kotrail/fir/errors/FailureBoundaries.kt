package com.kitakkun.kotrail.fir.errors

import com.kitakkun.kotrail.KotrailFailureBoundaries
import com.kitakkun.kotrail.exclude.Glob
import com.kitakkun.kotrail.fir.isOfThisModule
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.InlineStatus
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirArgumentList
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirCall
import org.jetbrains.kotlin.fir.expressions.FirCatch
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirResolvable
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenBranch
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.scopes.overriddenFunctions
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.isSuspendOrKSuspendFunctionType
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.unwrapFakeOverrides
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * The root a `try` sits at the top of, when that root is a failure boundary: the place where an
 * exception nothing catches ends a unit of work (a coroutine, a listener call, a thread) instead
 * of reaching a caller that could handle it.
 *
 * [description] names the root for a message (`launch`, `onClick`, `OnClickListener`).
 * [launcher] is the call whose lambda, or whose flow chain, is the root; it is `null` for an
 * override or a converted lambda. [downstream] lists the flow operators between the try's lambda
 * and the terminal call, nearest first. [enclosingTries] are the tries between the try and the
 * root whose try block holds it.
 */
class FailureBoundary(
    val description: String,
    val launcher: FirCall?,
    val downstream: List<FirFunctionCall>,
    val enclosingTries: List<FirTryExpression>,
)

/**
 * The failure boundary the checked `try` (the last of [CheckerContext.containingElements]) sits
 * at the top of, or `null` when it sits anywhere else.
 *
 * A try is at the top of a root body when the path from the body down to it passes only through
 * blocks, `if`/`when`, loops, other tries, returns, local variable initializers and assignments,
 * and the lambdas of inline functions (`apply`, `let`, `use`, `forEach`, `repeat`), which run in
 * place. Any other lambda, a nested function, a class, a constructor, an `init` block or a
 * property initializer ends the search without a root. A root body is:
 *
 * - the lambda passed to one of [KotrailFailureBoundaries.functions] (`launch`, `thread`,
 *   `LaunchedEffect`), or a lambda of a flow chain that is the receiver of one of them
 *   (`events.onEach { }.launchIn(scope)`);
 * - a lambda that returns `Unit`, converted to an interface declared outside this module
 *   (`view.setOnClickListener { }`, `Runnable { }`), unless the interface's function is suspend;
 * - the body of a function, not suspend, that overrides a member declared outside this module
 *   and returning `Unit` (`onClick`, `Runnable.run`), the members of `Any` aside: `toString` and
 *   its kin are called by the project's own code;
 * - the body of a function the project declares a boundary: annotated with one of
 *   [KotrailFailureBoundaries.annotations] (or a member of a class that is), or named by
 *   [KotrailFailureBoundaries.names].
 *
 * A library calls a `Unit` callback and forgets it, so nothing receives what escapes it. A member
 * that returns a value (`ClassLoader.loadClass`, a command handler's result) is called by code
 * that consumes the result, and that code receives the failure with it. So is a suspend function,
 * whose failure goes to the coroutine that called it.
 *
 * `runCatching` is inline but catches everything, so a try inside it is never at a boundary.
 */
context(context: CheckerContext)
fun failureBoundary(definition: KotrailFailureBoundaries): FailureBoundary? {
    val elements = context.containingElements
    val session = context.session
    val functions = definition.functions.map(::Glob)
    val tries = mutableListOf<FirTryExpression>()
    var child = elements.lastOrNull() ?: return null
    var index = elements.lastIndex - 1
    while (index >= 0) {
        val element = elements[index]
        when (element) {
            is FirTryExpression -> if (element.tryBlock === child) tries += element
            is FirBlock, is FirWhenExpression, is FirWhenBranch, is FirWhileLoop, is FirDoWhileLoop, is FirCatch,
            is FirReturnExpression, is FirVariableAssignment, is FirElvisExpression -> {}
            is FirProperty -> if (!element.isLocal) return null
            is FirSafeCallExpression -> if (element.selector !== child) return null
            is FirFunctionCall -> if (element.explicitReceiver?.unwrapSmartCast() !== child) return null
            is FirAnonymousFunction -> {
                val argument = lambdaArgument(elements, index) ?: return null
                argument.root(element, functions, session, elements, tries)?.let { return it }
                if (element.inlineStatus != InlineStatus.Inline || argument.calleeName == RUN_CATCHING) return null
                child = argument.call
                index = argument.callIndex - 1
                continue
            }
            is FirNamedFunction -> {
                val owner = elements.getOrNull(index - 1) as? FirClass
                val root = element.overridesOutsideCallback(owner, session, context.scopeSession) ||
                    element.isDeclaredBoundary(owner, definition, session)
                return if (root) {
                    FailureBoundary(element.name.asString(), launcher = null, downstream = emptyList(), enclosingTries = tries)
                } else {
                    null
                }
            }
            else -> return null
        }
        child = element
        index--
    }
    return null
}

private const val RUN_CATCHING = "kotlin.runCatching"
private val FLOW_PACKAGE = FqName("kotlinx.coroutines.flow")

/** A lambda as the argument of [call], which sits at [callIndex] of the checker's path; [parameterType] is the type of the parameter it is passed for. */
private class LambdaArgument(val call: FirCall, val callIndex: Int, val parameterType: ConeKotlinType?) {
    val callee: FirCallableSymbol<*>? = (call as? FirResolvable)?.calleeReference?.toResolvedCallableSymbol()
    val calleeName: String? = callee?.callableId?.asSingleFqName()?.asString()

    fun root(
        lambda: FirAnonymousFunction,
        functions: List<Glob>,
        session: FirSession,
        elements: List<FirElement>,
        tries: List<FirTryExpression>,
    ): FailureBoundary? {
        if (callee != null && callee.matches(functions)) {
            return FailureBoundary(callee.rootName(), launcher = call, downstream = emptyList(), enclosingTries = tries)
        }
        val isSuspendLambda = lambda.typeRef.coneType.isSuspendOrKSuspendFunctionType(session)
        if (lambda.returnTypeRef.coneType.lowerBoundIfFlexible().isUnit && !isSuspendLambda) {
            convertedInterface(session)?.let { name ->
                return FailureBoundary(name, launcher = null, downstream = emptyList(), enclosingTries = tries)
            }
        }
        return flowChainRoot(functions, elements, tries)
    }

    /** The interface declared outside this module that the lambda becomes: through a SAM constructor (`Runnable { }`) or a parameter of that type. */
    private fun convertedInterface(session: FirSession): String? {
        val type = if (callee?.origin == FirDeclarationOrigin.SamConstructor) (call as? FirExpression)?.resolvedType else parameterType
        val expanded = type?.lowerBoundIfFlexible()?.fullyExpandedType(session) ?: return null
        if (expanded.isSomeFunctionType(session)) return null
        val symbol = expanded.toRegularClassSymbol(session) ?: return null
        if (symbol.classKind != ClassKind.INTERFACE || symbol.isOfThisModule(session)) return null
        return symbol.classId.shortClassName.asString()
    }

    /**
     * The terminal call of the flow chain the lambda's call belongs to, when it is a boundary:
     * `events.onEach { }.map { }.launchIn(scope)`. Each link is the receiver of the next and
     * a flow; the links between the lambda's call and the terminal are downstream of it.
     */
    private fun flowChainRoot(functions: List<Glob>, elements: List<FirElement>, tries: List<FirTryExpression>): FailureBoundary? {
        var current = call as? FirFunctionCall ?: return null
        val downstream = mutableListOf<FirFunctionCall>()
        var index = callIndex - 1
        while (index >= 0) {
            val next = elements[index] as? FirFunctionCall ?: return null
            if (next.explicitReceiver?.unwrapSmartCast() !== current) return null
            if (current.resolvedType.lowerBoundIfFlexible().classId?.packageFqName != FLOW_PACKAGE) return null
            val callee = next.calleeReference.toResolvedCallableSymbol()
            if (callee != null && callee.matches(functions)) {
                return FailureBoundary(callee.rootName(), launcher = next, downstream = downstream, enclosingTries = tries)
            }
            downstream += next
            current = next
            index--
        }
        return null
    }
}

/**
 * The call [elements] holds just above the anonymous function at [index], with the parameter it
 * is passed for. Between the argument list and the function there may be a wrapper the compiler
 * adds (a named argument, a SAM conversion), which is the argument the mapping knows.
 */
private fun lambdaArgument(elements: List<FirElement>, index: Int): LambdaArgument? {
    val function = elements[index] as FirAnonymousFunction
    var listIndex = index - 1
    while (listIndex >= 0 && elements[listIndex] !is FirArgumentList) {
        val element = elements[listIndex]
        if (element !is FirExpression || element is FirCall || index - listIndex > 2) return null
        listIndex--
    }
    val call = elements.getOrNull(listIndex - 1) as? FirCall ?: return null
    val wrapper = elements.getOrNull(listIndex + 1)
    val parameter = call.resolvedArgumentMapping?.entries?.firstOrNull { (argument, _) ->
        argument === wrapper || argument.holds(function)
    }?.value
    return LambdaArgument(call, listIndex - 1, parameter?.returnTypeRef?.coneType)
}

private fun FirExpression.holds(function: FirAnonymousFunction): Boolean = when (this) {
    is FirAnonymousFunctionExpression -> anonymousFunction === function
    is FirWrappedArgumentExpression -> expression.holds(function)
    else -> false
}

private fun FirExpression.unwrapSmartCast(): FirExpression = (this as? FirSmartCastExpression)?.originalExpression ?: this

/** Matched by its own name and, for a member reached through a subtype, by the member it copies: `ThreadPoolExecutor.execute` is `Executor.execute`. */
private fun FirCallableSymbol<*>.matches(functions: List<Glob>): Boolean {
    val names = listOfNotNull(callableId, unwrapFakeOverrides().callableId).map { it.asSingleFqName().asString() }
    return names.any { name -> functions.any { it.matches(name) } }
}

/** `launch`, `thread`, or the class name for a constructor (`Thread`). */
private fun FirCallableSymbol<*>.rootName(): String =
    if (this is FirConstructorSymbol) callableId?.classId?.shortClassName?.asString() ?: name.asString() else name.asString()

/**
 * Whether the function overrides a member declared outside this module that returns `Unit`, other
 * than one of `Any`'s, and is not suspend. The return type is the member's as the class sees it,
 * so `accept` of a `Consumer<Event>` counts, while `call` of a `Callable<String>` does not.
 */
private fun FirNamedFunction.overridesOutsideCallback(owner: FirClass?, session: FirSession, scopeSession: ScopeSession): Boolean {
    if (owner == null || !isOverride || isSuspend) return false
    val overridden = symbol.overriddenFunctions(owner.symbol, session, scopeSession)
    return overridden.any { function ->
        val original = function.unwrapFakeOverrides()
        original.callableId?.classId != StandardClassIds.Any && !original.isOfThisModule(session) &&
            function.resolvedReturnType.lowerBoundIfFlexible().isUnit
    }
}

/** Whether the project names the function a boundary, by an annotation on it or on its class, or by its fully qualified name. */
private fun FirNamedFunction.isDeclaredBoundary(owner: FirClass?, definition: KotrailFailureBoundaries, session: FirSession): Boolean {
    val annotationGlobs = definition.annotations.map(::Glob)
    fun FirAnnotationContainer.annotated(): Boolean = annotations.any { annotation ->
        val name = annotation.toAnnotationClassId(session)?.asSingleFqName()?.asString()
        name != null && annotationGlobs.any { it.matches(name) }
    }
    if (annotationGlobs.isNotEmpty() && (annotated() || owner?.annotated() == true)) return true
    val name = symbol.callableId?.asSingleFqName()?.asString() ?: return false
    return definition.names.any { Glob(it).matches(name) }
}


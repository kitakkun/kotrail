package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailIdempotent
import com.kitakkun.kotrail.compat.qualifierClassId
import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.exclude.Glob
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.fqNameMatchesAny
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.containingClassLookupTag
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirBasicDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirCheckNotNullCall
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirImplicitInvokeCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirBackingFieldSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isNullableNothing
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.text
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * Holds an `@Idempotent` function, or setter, to its promise that a second call changes nothing
 * the first did not:
 *
 * ```kotlin
 * @Idempotent
 * fun refresh(items: List<Item>) {
 *     shown = items                     // fine: the same value again
 *     refreshes++                       // reported: a second call counts again
 *     history.add(items)                // reported: a second call adds again
 *     lastRefresh = Clock.System.now()  // reported: a second call stores another value
 * }
 * ```
 *
 * The body is read for writes to state the function does not own: a property, a captured
 * variable, a parameter's contents, or a local that aliases one of them. A local declared in the
 * function, and an object it creates there (`StringBuilder()`, `mutableListOf()`, the receiver of
 * `buildString { }` or `coroutineScope { }`), are its own, and anything is fine on them. On the
 * state it does not own the rule reports a read-modify-write (`x++`, `x += 1`, `x = x + 1`,
 * `xs[i] = xs[i] + 1`), a call from a table of operations that repeat (adding to or removing an
 * end of a list, appending to a builder, sending, emitting, launching, an atomic increment) or
 * from `deny`, and a store of a value that differs per call (the time, a random number, a UUID).
 * Assignments that do not read the previous value, `Set.add`, `Map.put` and `remove` are
 * idempotent and pass. A lambda is read only when it runs within the call (an inline function's,
 * or `withContext`'s); a listener registered by the function runs later and is not part of it.
 *
 * Calls the rule knows nothing about pass, unless `strict` is on: then every call must reach a
 * function that is `@Idempotent`, in `allow`, part of the standard library, or an operation on
 * a collection, a local object or a local function.
 */
object IdempotentChecker : FirBasicDeclarationChecker(MppCheckerKind.Common) {
    private const val SET_OR_ADD = "use a Set, or set(index, …) instead of add"

    /** Calls that do something again on each call, with what a second call would do and the way out. */
    private class Repeating(val functions: List<Glob>, val effect: String, val wayOut: String)

    private val REPEATING = listOf(
        Repeating(
            globs(
                "kotlin.text.StringBuilder.append*", "kotlin.text.StringBuilder.insert", "java.lang.StringBuilder.append*",
                "java.lang.StringBuilder.insert", "java.lang.AbstractStringBuilder.append*", "java.lang.Appendable.append",
                "kotlin.text.Appendable.append", "kotlin.text.append", "kotlin.text.appendLine",
            ),
            "append twice",
            "make the write idempotent (build the text in a local and assign it)",
        ),
        Repeating(
            globs("kotlinx.coroutines.channels.SendChannel.send", "kotlinx.coroutines.channels.SendChannel.trySend", "kotlinx.coroutines.channels.trySendBlocking"),
            "send twice",
            "make the write idempotent (publish the state through a MutableStateFlow, which drops an equal value)",
        ),
        Repeating(
            globs("kotlinx.coroutines.flow.MutableSharedFlow.emit", "kotlinx.coroutines.flow.MutableSharedFlow.tryEmit", "kotlinx.coroutines.flow.FlowCollector.emit"),
            "emit twice",
            "make the write idempotent (publish the state through a MutableStateFlow, which drops an equal value)",
        ),
        Repeating(
            globs("kotlinx.coroutines.launch", "kotlinx.coroutines.async", "kotlinx.coroutines.flow.launchIn"),
            "start the work twice",
            "keep the Job and start the work only when none is active",
        ),
        Repeating(
            globs(
                "java.util.concurrent.atomic.*.incrementAndGet", "java.util.concurrent.atomic.*.decrementAndGet",
                "java.util.concurrent.atomic.*.getAndIncrement", "java.util.concurrent.atomic.*.getAndDecrement",
                "java.util.concurrent.atomic.*.addAndGet", "java.util.concurrent.atomic.*.getAndAdd",
                "java.util.concurrent.atomic.LongAdder.increment", "java.util.concurrent.atomic.LongAdder.decrement",
                "java.util.concurrent.atomic.LongAdder.add",
                "kotlin.concurrent.atomics.*.incrementAndFetch", "kotlin.concurrent.atomics.*.decrementAndFetch",
                "kotlin.concurrent.atomics.*.fetchAndIncrement", "kotlin.concurrent.atomics.*.fetchAndDecrement",
                "kotlin.concurrent.atomics.*.addAndFetch", "kotlin.concurrent.atomics.*.fetchAndAdd",
                "kotlin.concurrent.atomics.plusAssign", "kotlin.concurrent.atomics.minusAssign",
                "kotlinx.atomicfu.*.incrementAndGet", "kotlinx.atomicfu.*.decrementAndGet",
                "kotlinx.atomicfu.*.getAndIncrement", "kotlinx.atomicfu.*.getAndDecrement",
                "kotlinx.atomicfu.*.addAndGet", "kotlinx.atomicfu.*.getAndAdd",
            ),
            "apply it twice",
            "make the write idempotent (store the target value with set)",
        ),
    )

    /** Functions whose value differs from call to call: storing it makes a second call write something else. */
    private val VARYING = globs(
        "kotlin.random.Random.*", "kotlin.collections.random*", "kotlin.collections.shuffled", "kotlin.ranges.random*",
        "java.util.Random.*", "java.util.concurrent.ThreadLocalRandom.*", "java.lang.Math.random", "java.security.SecureRandom.*",
        "java.lang.System.currentTimeMillis", "java.lang.System.nanoTime",
        "kotlin.time.Clock.System.now", "kotlinx.datetime.Clock.System.now", "kotlin.time.TimeSource.Monotonic.markNow",
        "kotlin.system.getTimeMillis", "kotlin.system.getTimeNanos", "kotlin.system.getTimeMicros",
        "java.time.*.now", "java.util.UUID.randomUUID", "kotlin.uuid.Uuid.Companion.random", "kotlin.uuid.Uuid.random",
    )

    /** Calls that put a value into the object they are called on. */
    private val STORES = setOf("set", "put", "add", "addAll", "putAll", "plusAssign", "emit", "tryEmit", "send", "trySend", "offer", "addFirst", "addLast")

    /** A list grows, or loses another element, on each of these. */
    private val LIST_REPEATING = setOf("add", "addAll", "plusAssign", "addFirst", "addLast", "removeAt", "removeFirst", "removeLast", "removeFirstOrNull", "removeLastOrNull")

    /** Operators that, applied to the previous value, give a different one each time; `or`, `and`, `maxOf` and an elvis settle. */
    private val ACCUMULATING = setOf("plus", "minus", "times", "div", "rem", "inc", "dec", "not", "unaryMinus", "inv", "shl", "shr", "ushr", "xor")

    /** Functions whose lambda runs within the call though they do not inline it. */
    private val IN_CALL = globs(
        "kotlinx.coroutines.withContext", "kotlinx.coroutines.coroutineScope", "kotlinx.coroutines.supervisorScope",
        "kotlinx.coroutines.runBlocking", "kotlinx.coroutines.withTimeout", "kotlinx.coroutines.withTimeoutOrNull",
    )

    /** Functions whose lambda receiver is a new object the function owns. */
    private val FRESH_RECEIVER = globs(
        "kotlin.text.buildString", "kotlin.collections.buildList", "kotlin.collections.buildSet", "kotlin.collections.buildMap",
        "kotlinx.coroutines.coroutineScope", "kotlinx.coroutines.supervisorScope", "kotlinx.coroutines.runBlocking",
        "kotlinx.coroutines.withContext",
    )

    /** Functions that return a new object. */
    private val FRESH_FACTORIES = globs(
        "kotlin.collections.mutableListOf", "kotlin.collections.arrayListOf", "kotlin.collections.mutableSetOf",
        "kotlin.collections.hashSetOf", "kotlin.collections.linkedSetOf", "kotlin.collections.mutableMapOf",
        "kotlin.collections.hashMapOf", "kotlin.collections.linkedMapOf", "kotlin.collections.toMutable*",
        "kotlin.collections.ArrayList", "kotlin.collections.HashMap", "kotlin.collections.HashSet",
        "kotlin.collections.ArrayDeque", "kotlin.text.StringBuilder",
        "kotlinx.coroutines.channels.Channel", "kotlinx.coroutines.flow.MutableSharedFlow", "kotlinx.coroutines.flow.MutableStateFlow",
        "kotlinx.coroutines.CoroutineScope", "kotlinx.coroutines.MainScope",
    )
    private val RECEIVER_SCOPE_FUNCTIONS = globs("kotlin.apply", "kotlin.run", "kotlin.with", "kotlin.also", "kotlin.let")

    /** What strict mode accepts without an annotation: the standard library, but not its I/O, its randomness or a lambda invoked. */
    private val KNOWN = globs("kotlin.*", "java.lang.String.*", "java.lang.Math.*", "java.util.Objects.*")
    private val NOT_KNOWN = globs("kotlin.io.*", "kotlin.Function*.invoke", "kotlin.coroutines.SuspendFunction*.invoke", "kotlin.jvm.functions.*")

    private val LIST = ClassId.topLevel(FqName("kotlin.collections.List"))
    private val SET = ClassId.topLevel(FqName("kotlin.collections.Set"))
    private val MAP = ClassId.topLevel(FqName("kotlin.collections.Map"))
    private val COLLECTION = ClassId.topLevel(FqName("kotlin.collections.Collection"))
    private val MUTABLE_STATE_FLOW = ClassId.topLevel(FqName("kotlinx.coroutines.flow.MutableStateFlow"))

    private fun globs(vararg patterns: String): List<Glob> = patterns.map(::Glob)

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirDeclaration) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.IDEMPOTENT)) return
        if (declaration.source?.kind is KtFakeSourceElementKind) return
        val (name, body) = when (declaration) {
            is FirNamedFunction -> declaration.name.asString() to declaration.body
            is FirPropertyAccessor -> if (declaration.isSetter) declaration.propertySymbol.name.asString() to declaration.body else return
            else -> return
        }
        if (body == null) return
        val session = context.session
        val annotations = config.idempotent.annotations.map { ClassId.topLevel(FqName(it)) }
        if (annotations.none { declaration.symbol.hasAnnotation(it, session) }) return

        val finder = RepeatFinder(session, config.idempotent, annotations)
        body.accept(finder)
        for (finding in finder.findings) {
            when (finding) {
                is Finding.Repeats -> reportKotrail(finding.source, KotrailDiagnostics.NOT_IDEMPOTENT, finding.operation, name, finding.effect)
                is Finding.Unknown -> reportKotrail(finding.source, KotrailDiagnostics.CALLEE_NOT_KNOWN_IDEMPOTENT, finding.callee, name)
            }
        }
    }

    private sealed class Finding(val source: KtSourceElement) {
        class Repeats(source: KtSourceElement, val operation: String, val effect: String) : Finding(source)
        class Unknown(source: KtSourceElement, val callee: String) : Finding(source)
    }

    /** Walks the part of a body that runs within the call, collecting every operation a second call would repeat. */
    private class RepeatFinder(
        private val session: FirSession,
        private val settings: KotrailIdempotent,
        private val annotations: List<ClassId>,
    ) : FirVisitorVoid() {
        val findings = mutableListOf<Finding>()

        /** Variables declared in the body: the function's own state. */
        private val locals = mutableSetOf<FirBasedSymbol<*>>()

        /** Locals and lambda receivers that hold an object the function created. */
        private val fresh = mutableSetOf<FirBasedSymbol<*>>()

        /** Locals that hold a value that differs per call. */
        private val varying = mutableSetOf<FirBasedSymbol<*>>()

        private val inCallLambdas = mutableSetOf<FirAnonymousFunction>()

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitProperty(property: FirProperty) {
            if (property.isLocal) {
                locals += property.symbol
                val initializer = property.initializer
                if (initializer != null && initializer.isFresh()) fresh += property.symbol
                if (initializer != null && initializer.varies()) varying += property.symbol
            }
            property.acceptChildren(this)
        }

        override fun visitVariableAssignment(variableAssignment: FirVariableAssignment) {
            variableAssignment.acceptChildren(this)
            val target = when (val lValue = variableAssignment.lValue) {
                is FirDesugaredAssignmentValueReferenceExpression -> lValue.expressionRef.value
                else -> lValue
            } as? FirQualifiedAccessExpression ?: return
            val symbol = target.calleeReference.toResolvedCallableSymbol() ?: return
            if (ownsState(target, symbol)) return
            val source = variableAssignment.source ?: return
            val name = symbol.name.asString()
            val value = variableAssignment.rValue
            when {
                source.kind is KtFakeSourceElementKind.DesugaredIncrementOrDecrement || value.accumulates { it.readsState(target, symbol) } ->
                    findings += Finding.Repeats(source, operationText(source, "$name = …"), "update '$name' twice. Remove @Idempotent, or assign a value that does not depend on the previous one")
                value.varies() ->
                    findings += Finding.Repeats(source, operationText(source, "$name = …"), "store a different value in '$name'. Remove @Idempotent, or take the value as a parameter")
            }
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            val callee = functionCall.calleeReference.toResolvedCallableSymbol() as? FirFunctionSymbol<*>
            markLambdas(functionCall, callee)
            functionCall.acceptChildren(this)
            if (callee == null || callee is FirConstructorSymbol) return
            val source = functionCall.source ?: return
            if (callee.fqNameMatchesAny(settings.allow)) return
            val receiver = functionCall.receiver()
            if (receiver != null && receiver.isFresh()) return
            val name = callee.name.asString()
            val operation = operationText(functionCall, receiver, name)
            val effect = repeatedEffect(functionCall, callee, receiver, name)
            when {
                effect != null -> findings += Finding.Repeats(source, operation, effect)
                settings.strict && !isKnown(callee, receiver) -> findings += Finding.Unknown(source, if (name == "invoke" && receiver != null) operationTarget(receiver).removeSurrounding("'") else callableName(callee))
            }
        }

        /** What a second call of [callee] would do again, with the way out, or `null` when it is idempotent as far as the rule knows. */
        private fun repeatedEffect(call: FirFunctionCall, callee: FirFunctionSymbol<*>, receiver: FirExpression?, name: String): String? {
            if (call.source?.kind is KtFakeSourceElementKind.DesugaredIncrementOrDecrement) {
                return "update ${operationTarget(receiver)} twice. Remove @Idempotent, or assign a value that does not depend on the previous one"
            }
            if (name == "set" && receiver != null) {
                val value = call.argumentList.arguments.lastOrNull()
                if (value != null && value.accumulates { it.isElementRead(receiver) }) {
                    return "update ${operationTarget(receiver)} twice. Remove @Idempotent, or assign a value that does not depend on the previous one"
                }
            }
            if (name in LIST_REPEATING && receiver != null && receiver.resolvedType.isSubtypeOf(LIST)) {
                val effect = if (name.startsWith("add") || name == "plusAssign") "add twice" else "remove another element"
                return "$effect. Remove @Idempotent, or make the write idempotent ($SET_OR_ADD)"
            }
            if (name in STORES && call.argumentList.arguments.any { it.varies() }) {
                return "store a different value. Remove @Idempotent, or take the value as a parameter"
            }
            for (repeating in REPEATING) {
                if (!callee.fqNameMatchesAny(repeating.functions)) continue
                if (receiver != null && receiver.resolvedType.isSubtypeOf(MUTABLE_STATE_FLOW)) continue
                return "${repeating.effect}. Remove @Idempotent, or ${repeating.wayOut}"
            }
            if (callee.fqNameMatchesAny(settings.deny)) return "do it twice. Remove @Idempotent, or make the call idempotent"
            return null
        }

        private fun isKnown(callee: FirFunctionSymbol<*>, receiver: FirExpression?): Boolean = when {
            annotations.any { callee.hasAnnotation(it, session) } -> true
            callee.isLocal -> true
            callee.fqNameMatchesAny(NOT_KNOWN) -> false
            callee.fqNameMatchesAny(KNOWN) || callee.fqNameMatchesAny(VARYING) -> true
            receiver != null && (receiver.resolvedType.isSubtypeOf(COLLECTION) || receiver.resolvedType.isSubtypeOf(MAP)) -> true
            else -> false
        }

        /** Lambdas the call runs before it returns are part of the function; any other lambda runs later. */
        private fun markLambdas(call: FirFunctionCall, callee: FirFunctionSymbol<*>?) {
            val mapping = call.resolvedArgumentMapping
            val inCall = callee != null && callee.fqNameMatchesAny(IN_CALL)
            val freshReceiver = callee != null && (
                callee.fqNameMatchesAny(FRESH_RECEIVER) ||
                    callee.fqNameMatchesAny(RECEIVER_SCOPE_FUNCTIONS) && scopedValue(call, callee)?.isFresh() == true
                )
            for (argument in call.argumentList.arguments) {
                val lambda = argument.lambda() ?: continue
                val parameter = mapping?.get(argument)
                val inlined = callee != null && callee.isInline && parameter != null && !parameter.isCrossinline && !parameter.isNoinline
                if (inlined || inCall) inCallLambdas += lambda
                if (freshReceiver) {
                    lambda.receiverParameter?.symbol?.let { fresh += it }
                    lambda.valueParameters.firstOrNull()?.symbol?.let { fresh += it }
                }
            }
        }

        override fun visitImplicitInvokeCall(implicitInvokeCall: FirImplicitInvokeCall) {
            visitFunctionCall(implicitInvokeCall)
        }

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
            if (anonymousFunction in inCallLambdas) anonymousFunction.acceptChildren(this)
        }

        override fun visitAnonymousObject(anonymousObject: FirAnonymousObject) {}

        override fun visitRegularClass(regularClass: FirRegularClass) {}

        private fun scopedValue(call: FirFunctionCall, callee: FirFunctionSymbol<*>): FirExpression? =
            if (callee.name.asString() == "with") call.argumentList.arguments.firstOrNull() else call.explicitReceiver

        /** Whether the property [target] reads is the function's own: a local declared in the body, or a member of an object the function created. */
        private fun ownsState(target: FirQualifiedAccessExpression, symbol: FirCallableSymbol<*>): Boolean = when {
            symbol is FirBackingFieldSymbol -> false
            symbol is FirPropertySymbol && symbol.isLocal -> symbol in locals
            else -> target.receiver()?.isFresh() == true
        }

        private fun FirExpression.isFresh(): Boolean = when (val expression = unwrap()) {
            is FirFunctionCall -> {
                val callee = expression.calleeReference.toResolvedCallableSymbol()
                callee is FirConstructorSymbol || callee != null && callee.fqNameMatchesAny(FRESH_FACTORIES)
            }
            is FirThisReceiverExpression -> (expression.calleeReference as? FirThisReference)?.boundSymbol.let { bound -> fresh.any { it == bound } }
            is FirPropertyAccessExpression -> expression.calleeReference.toResolvedCallableSymbol().let { callee -> fresh.any { it == callee } }
            else -> false
        }

        /** Whether this value, not looking into lambdas, comes from a call whose result differs per call. */
        private fun FirExpression.varies(): Boolean {
            var found = false
            accept(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (!found) element.acceptChildren(this)
                }

                override fun visitFunctionCall(functionCall: FirFunctionCall) {
                    val callee = functionCall.calleeReference.toResolvedCallableSymbol()
                    if (callee != null && callee.fqNameMatchesAny(VARYING)) found = true else functionCall.acceptChildren(this)
                }

                override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
                    if (propertyAccessExpression.calleeReference.toResolvedCallableSymbol().let { callee -> varying.any { it == callee } }) found = true
                    else propertyAccessExpression.acceptChildren(this)
                }
            })
            return found
        }

        /** Whether this value applies an accumulating operator to an operand that [reads] accepts. */
        private fun FirExpression.accumulates(reads: (FirExpression) -> Boolean): Boolean {
            var found = false
            accept(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (!found) element.acceptChildren(this)
                }

                override fun visitFunctionCall(functionCall: FirFunctionCall) {
                    val name = functionCall.calleeReference.name.asString()
                    if (name in ACCUMULATING) {
                        val operands = listOfNotNull(functionCall.explicitReceiver) + functionCall.argumentList.arguments
                        val settles = (name == "plus" || name == "minus") && operands.firstOrNull()?.resolvedType?.let { it.isSubtypeOf(SET) || it.isSubtypeOf(MAP) } == true
                        if (!settles && operands.any(reads)) {
                            found = true
                            return
                        }
                    }
                    functionCall.acceptChildren(this)
                }

                override fun visitStringConcatenationCall(stringConcatenationCall: FirStringConcatenationCall) {
                    if (stringConcatenationCall.argumentList.arguments.any(reads)) found = true
                    else stringConcatenationCall.acceptChildren(this)
                }
            })
            return found
        }

        /** Whether this reads the property [symbol] on the same receiver as [target], or something reached through it. */
        private fun FirExpression.readsState(target: FirQualifiedAccessExpression, symbol: FirCallableSymbol<*>): Boolean {
            val expression = unwrap() as? FirQualifiedAccessExpression ?: return false
            if (expression is FirPropertyAccessExpression && expression.calleeReference.toResolvedCallableSymbol() == symbol &&
                sameValue(expression.receiver(), target.receiver())
            ) return true
            return expression.explicitReceiver?.readsState(target, symbol) == true
        }

        /** Whether this reads an element of [collection]: `collection[i]`, `collection.get(i)`. */
        private fun FirExpression.isElementRead(collection: FirExpression): Boolean {
            val call = unwrap() as? FirFunctionCall ?: return false
            return call.calleeReference.name.asString() == "get" && sameValue(call.receiver(), collection)
        }

        private fun sameValue(a: FirExpression?, b: FirExpression?): Boolean {
            if (a == null || b == null) return a == null && b == null
            val left = a.unwrap()
            val right = b.unwrap()
            return when {
                left is FirThisReceiverExpression && right is FirThisReceiverExpression ->
                    (left.calleeReference as? FirThisReference)?.boundSymbol == (right.calleeReference as? FirThisReference)?.boundSymbol
                left is FirResolvedQualifier && right is FirResolvedQualifier -> left.qualifierClassId != null && left.qualifierClassId == right.qualifierClassId
                left is FirPropertyAccessExpression && right is FirPropertyAccessExpression ->
                    left.calleeReference.toResolvedCallableSymbol().let { it != null && it == right.calleeReference.toResolvedCallableSymbol() } &&
                        sameValue(left.receiver(), right.receiver())
                else -> false
            }
        }

        private fun FirQualifiedAccessExpression.receiver(): FirExpression? = explicitReceiver ?: dispatchReceiver ?: extensionReceiver

        private fun FirExpression.unwrap(): FirExpression = when (this) {
            is FirSmartCastExpression -> originalExpression.unwrap()
            is FirWrappedArgumentExpression -> expression.unwrap()
            is FirCheckNotNullCall -> argumentList.arguments.firstOrNull()?.unwrap() ?: this
            is FirElvisExpression -> lhs.unwrap()
            is FirBlock -> (statements.lastOrNull() as? FirExpression)?.unwrap() ?: this
            else -> this
        }

        private fun FirExpression.lambda(): FirAnonymousFunction? = when (this) {
            is FirAnonymousFunctionExpression -> anonymousFunction
            is FirWrappedArgumentExpression -> expression.lambda()
            else -> null
        }

        private fun ConeKotlinType.isSubtypeOf(classId: ClassId): Boolean {
            val expanded = fullyExpandedType(session)
            val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) ?: return false
            val superType = classId.constructClassLikeType(Array(symbol.typeParameterSymbols.size) { ConeStarProjection }, isMarkedNullable = true)
            return !expanded.isNullableNothing && AbstractTypeChecker.isSubtypeOf(session.typeContext, expanded, superType)
        }

        /** The call as written when it is an operator (`items += x`, `counts[k] = v`), `items.add` for a call on a short, one-line receiver, the function name otherwise. */
        private fun operationText(call: FirFunctionCall, receiver: FirExpression?, name: String): String {
            val source = call.source
            val text = source?.text?.toString()
            if (source != null && text != null && name !in text) return operationText(source, name)
            val written = call.explicitReceiver?.source?.takeIf { it.kind !is KtFakeSourceElementKind }?.text?.toString()
            return if (written != null && receiver != null && '\n' !in written && written.length <= MAX_RECEIVER_TEXT) "$written.$name" else name
        }

        private fun operationText(source: KtSourceElement, fallback: String): String {
            val written = source.text?.toString()
            return if (written != null && '\n' !in written && written.length <= MAX_OPERATION_TEXT) written else fallback
        }

        private fun operationTarget(receiver: FirExpression?): String {
            val written = receiver?.source?.text?.toString()
            return if (written != null && '\n' !in written && written.length <= MAX_RECEIVER_TEXT) "'$written'" else "the element"
        }

        private fun callableName(callee: FirFunctionSymbol<*>): String {
            val className = callee.containingClassLookupTag()?.classId?.shortClassName ?: return callee.name.asString()
            return "$className.${callee.name}"
        }
    }

    private const val MAX_RECEIVER_TEXT = 30
    private const val MAX_OPERATION_TEXT = 60
}

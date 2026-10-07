package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirCheckedSafeCallSubject
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isNullableNothing
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * Reports a resource created in a function and neither closed nor handed on:
 *
 * ```kotlin
 * val reader = File(path).bufferedReader()      // reported: nothing closes it
 * return reader.readLine()
 *
 * File(path).bufferedReader().use { it.readLine() }          // fine
 * val reader = File(path).bufferedReader(); try { ... } finally { reader.close() }   // fine
 * fun open(path: String): BufferedReader = File(path).bufferedReader()              // fine: the caller owns it
 * ```
 *
 * A resource is a constructor call whose class is an `AutoCloseable`, or a call to one of the
 * listed factories (`mustClose.factories`: the `kotlin.io` stream, reader and writer factories,
 * `java.nio.file.Files.new*`); a function that merely returns an existing resource (an
 * `inputStream` property, a pooled connection) is not one, since the rule cannot tell whether
 * the caller owns it. The resource is fine when it is the receiver of `use { }` (through scope
 * functions and safe calls), a local the body closes with `close()` or `use`, or when it leaves
 * the function: returned, assigned to a property, passed as an argument, or the receiver of a
 * bound callable reference (`thread(block = reader::close)`), where whoever receives it is the
 * owner. Everything else is a leak on every path, reported at the creation.
 *
 * A creation that takes, as an argument, a resource this function did not create (a parameter,
 * `this`, a property, a call that is not a creation) is a wrapper over a borrowed stream: borrowed
 * because the function received the stream rather than created it, so it belongs to whoever lent
 * it. Closing the wrapper would close the lender's stream, so the wrapper is not this function's
 * to close and is not tracked. A wrapper chained on such a wrapper
 * (`Wrapper(parameterStream).bufferedReader()`), or on a stream reached from a parameter or `this`
 * (`parameterStream.bufferedReader()`, `this.stream.buffered()`), wraps a borrowed stream too, and
 * so does a creation around such a wrapper (`BufferedReader(InputStreamReader(parameterStream))`).
 *
 * A stream its source owns is not the function's to close either. A source is an instance of a
 * class on `mustClose.streamOwners`; its streams are the `inputStream`, `outputStream` and
 * `errorStream` properties and their getters, `getInputStream(entry)` of an archive included.
 * The streams of a `Process` (`process.inputStream.bufferedReader()`) are always the process's.
 * Those of an `HttpURLConnection` are the connection's when the function disconnects it
 * (`connection.disconnect()` anywhere in the body). Those of any other source, a `Socket` or a
 * `ZipFile`, are the source's when the function closes it: the source reads a local, parameter
 * or property the body calls `close()` or `use` on, or the parameter of a `use { }` lambda
 * (`Socket(host, port).use { it.getInputStream().bufferedReader() }`). They are the source's as
 * well when the source is a local holding a creation of this function, which carries the
 * obligation itself and is reported if nothing settles it. A wrapper over such a stream is not tracked either.
 */
object MustCloseChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    private val CLOSEABLE = listOf(
        ClassId(FqName("kotlin"), org.jetbrains.kotlin.name.Name.identifier("AutoCloseable")),
        ClassId(FqName("java.lang"), org.jetbrains.kotlin.name.Name.identifier("AutoCloseable")),
        ClassId(FqName("java.io"), org.jetbrains.kotlin.name.Name.identifier("Closeable")),
    )
    /** Closes the receiver when done: `use`, and the readers' consuming helpers (`readText` is not one; it leaves the reader open). */
    private val USE = setOf(
        "kotlin.use", "kotlin.io.use", "kotlin.AutoCloseable.use",
        "kotlin.io.useLines", "kotlin.io.forEachLine", "kotlin.io.readLines", "kotlin.io.path.useLines", "kotlin.io.path.forEachLine",
    )
    /** Closes the receiver when the lambda ends; the lambda's parameter is that receiver under another name. */
    private val CLOSING_USE = setOf("kotlin.use", "kotlin.io.use", "kotlin.AutoCloseable.use")
    private val SCOPE_FUNCTIONS = setOf("kotlin.apply", "kotlin.also", "kotlin.let", "kotlin.run", "kotlin.takeIf", "kotlin.takeUnless")

    /** Wraps the receiver in another resource whose closing closes it: settling the wrapper settles what it wraps. */
    private val WRAPPERS = setOf(
        "kotlin.io.buffered", "kotlin.io.bufferedReader", "kotlin.io.bufferedWriter", "kotlin.io.reader", "kotlin.io.writer",
        "kotlin.io.printWriter", "okio.buffer",
    )
    private val RESULT_OF_LAMBDA = USE + setOf("kotlin.let", "kotlin.run", "kotlin.with")

    private val PROCESS = ClassId(FqName("java.lang"), org.jetbrains.kotlin.name.Name.identifier("Process"))
    private val HTTP_URL_CONNECTION = ClassId(FqName("java.net"), org.jetbrains.kotlin.name.Name.identifier("HttpURLConnection"))
    private val DISCONNECT = org.jetbrains.kotlin.name.Name.identifier("disconnect")

    /** The streams a source hands out, read as properties or through their getters (`ZipFile.getInputStream(entry)` takes the entry). */
    private val SOURCE_STREAMS = setOf(
        "inputStream", "errorStream", "outputStream", "getInputStream", "getErrorStream", "getOutputStream",
    )

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.MUST_CLOSE)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val body = declaration.body ?: return
        val streamOwners = config.mustClose.streamOwners.map { ClassId.topLevel(FqName(it)) }
        val finder = Finder(context.session, config.mustClose.factories.toSet(), config.mustClose.ignoredTypes, streamOwners)
        finder.collectReleases(body)
        body.accept(finder)
        for ((call, type) in finder.leaked()) {
            val at = call.source ?: continue
            if (at.kind is KtFakeSourceElementKind) continue
            reportKotrail(at, KotrailDiagnostics.RESOURCE_NOT_CLOSED, type)
        }
    }

    /** Finds the resources a body creates and which of them are closed or handed on. */
    private class Finder(
        private val session: FirSession,
        private val factories: Set<String>,
        private val ignoredTypes: List<String>,
        private val streamOwners: List<ClassId>,
    ) : FirVisitorVoid() {
        private val created = LinkedHashMap<FirFunctionCall, String>()
        private val settled = HashSet<FirFunctionCall>()
        private val locals = HashMap<FirBasedSymbol<*>, FirFunctionCall>()
        private val settledLocals = HashSet<FirBasedSymbol<*>>()
        private val ancestors = ArrayList<FirElement>()

        /** Every local's initializer, a stream taken from a source included. */
        private val localInitializers = HashMap<FirBasedSymbol<*>, FirExpression>()

        /** The connections the body calls `disconnect()` on. */
        private val disconnected = HashSet<FirBasedSymbol<*>>()

        /** What the body calls `close()` or `use` on, and the parameters of `use { }` lambdas, each the closed receiver by another name. */
        private val closed = HashSet<FirBasedSymbol<*>>()

        fun leaked(): List<Pair<FirFunctionCall, String>> = created.entries
            .filter { (call, _) -> call !in settled && locals.entries.none { (local, creation) -> creation === call && local in settledLocals } }
            .map { (call, type) -> call to type }

        override fun visitElement(element: FirElement) {
            ancestors.add(element)
            element.acceptChildren(this)
            ancestors.removeAt(ancestors.lastIndex)
        }

        /**
         * Records the connections the body calls `disconnect()` on and what it closes, wherever the call sits,
         * so a release in a `finally` below a read counts; it runs before the walk.
         */
        fun collectReleases(body: FirElement) {
            body.accept(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    element.acceptChildren(this)
                }

                override fun visitFunctionCall(functionCall: FirFunctionCall) {
                    val callee = functionCall.calleeReference.toResolvedCallableSymbol()
                    val receiver = functionCall.explicitReceiver
                    if (callee?.name == DISCONNECT && functionCall.argumentList.arguments.isEmpty() && receiver != null) {
                        // The type as smart cast; the symbol behind the cast.
                        if (receiver.resolvedType.isSubtypeOf(HTTP_URL_CONNECTION)) receiver.referencedSymbol()?.let { disconnected += it }
                    }
                    if (functionCall.settlesReceiver()) receiver?.referencedSymbol()?.let { closed += it }
                    if (functionCall.fqName() in CLOSING_USE) {
                        val lambda = functionCall.argumentList.arguments.lastOrNull()?.unwrapArgument() as? FirAnonymousFunctionExpression
                        lambda?.anonymousFunction?.valueParameters?.forEach { closed += it.symbol }
                    }
                    functionCall.acceptChildren(this)
                }
            })
        }

        override fun visitProperty(property: FirProperty) {
            property.initializer?.let { localInitializers[property.symbol] = it }
            val initializer = property.initializer?.let { unwrapScopeChain(it) } as? FirFunctionCall
            if (initializer != null) locals[property.symbol] = initializer
            visitElement(property)
        }

        override fun visitSafeCallExpression(safeCallExpression: FirSafeCallExpression) {
            val selector = safeCallExpression.selector as? FirFunctionCall
            if (selector != null && selector.settlesReceiver()) {
                safeCallExpression.receiver.localSymbol()?.let { settledLocals += it }
            }
            visitElement(safeCallExpression)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            val type = resourceType(functionCall)
            if (type != null && !wrapsBorrowed(functionCall, HashSet())) {
                created[functionCall] = type
                if (isSettledAbove(functionCall)) settled += functionCall
            }
            if (functionCall.settlesReceiver()) {
                functionCall.explicitReceiver?.localSymbol()?.let { settledLocals += it }
            }
            // `sink.buffer().use { }`: the wrapper's closing closes the local it wraps.
            if (functionCall.fqName() in WRAPPERS && isSettledAbove(functionCall)) {
                functionCall.explicitReceiver?.localSymbol()?.let { settledLocals += it }
            }
            // A resource handed to another call is that call's to close.
            for (argument in functionCall.argumentList.arguments) {
                val expression = argument.unwrapArgument().unwrapped()
                expression.localSymbol()?.let { settledLocals += it }
                (unwrapScopeChain(expression) as? FirFunctionCall)?.let { settled += it }
            }
            visitElement(functionCall)
        }

        /** `thread(block = reader::close)`, `onDispose(reader::stop)`: the reference escapes the object, so whoever holds it closes it. */
        override fun visitCallableReferenceAccess(callableReferenceAccess: FirCallableReferenceAccess) {
            callableReferenceAccess.explicitReceiver?.let { receiver ->
                receiver.localSymbol()?.let { settledLocals += it }
                (unwrapScopeChain(receiver) as? FirFunctionCall)?.let { settled += it }
            }
            visitElement(callableReferenceAccess)
        }

        /** `close()`, and `use { }` on a local: the local is settled either way. */
        private fun FirFunctionCall.settlesReceiver(): Boolean {
            val name = calleeReference.toResolvedCallableSymbol()?.name?.asString()
            return (name == "close" && argumentList.arguments.isEmpty()) || fqName() in USE
        }

        /** A constructor of an `AutoCloseable`, or a listed factory: a resource this body owns; an in-memory type on the ignored list is not one. */
        private fun resourceType(call: FirFunctionCall): String? {
            if (!isCreation(call)) return null
            if (ignoredTypes.any { call.resolvedType.isSubtypeOf(ClassId.topLevel(FqName(it))) }) return null
            return call.resolvedType.renderReadable()
        }

        /** A constructor of an `AutoCloseable` or a listed factory, whether or not its type is on the ignored list. */
        private fun isCreation(call: FirFunctionCall): Boolean {
            val callee = call.calleeReference.toResolvedCallableSymbol() ?: return false
            return when (callee) {
                is FirConstructorSymbol -> call.resolvedType.isCloseable()
                else -> callee.callableId?.asSingleFqName()?.asString() in factories
            }
        }

        /**
         * `BoundedReader(this, n)`, `BufferedReader(parameterReader)`: an argument is a resource this
         * function did not create, so the new object is a wrapper over a borrowed stream, which its lender closes.
         * An argument that is a creation, directly, through a scope chain or through a local holding one, is
         * borrowed when it wraps a borrowed stream itself: `BufferedReader(InputStreamReader(parameterStream))`.
         * `BufferedReader(FileReader(path))` and a wrapper over a local holding a fresh creation stay tracked.
         */
        private fun wrapsBorrowedArgument(call: FirFunctionCall, visited: MutableSet<FirFunctionCall>): Boolean =
            call.argumentList.arguments.any { argument ->
                val expression = argument.unwrapArgument().unwrapped()
                if (expression is FirAnonymousFunctionExpression || !expression.resolvedType.isCloseable()) return@any false
                val local = expression.localSymbol()
                val inner = if (local != null) locals.getValue(local) else unwrapScopeChain(expression) as? FirFunctionCall
                inner == null || !isCreation(inner) || wrapsBorrowed(inner, visited)
            }

        /**
         * A wrapper over a borrowed stream: a creation over a borrowed argument, or a wrapper chained on one.
         * [visited] holds the creations already asked about in this walk; one met again answers no.
         */
        private fun wrapsBorrowed(call: FirFunctionCall, visited: MutableSet<FirFunctionCall>): Boolean =
            visited.add(call) && isCreation(call) && (wrapsBorrowedArgument(call, visited) || isChainedOnBorrowed(call, visited))

        /**
         * `Wrapper(parameterStream).bufferedReader()`, `parameterStream.bufferedReader()`, `this.stream.buffered()`:
         * a creation that wraps its receiver, where the receiver is itself a wrapper over a borrowed stream, a
         * stream reached from a parameter or `this`, or a stream its source owns ([isOwnedBySource]), wraps a
         * stream someone else closes. `File(path).bufferedReader()` and `request.body.bufferedReader()` on a
         * local stay tracked.
         */
        private fun isChainedOnBorrowed(call: FirFunctionCall, visited: MutableSet<FirFunctionCall>): Boolean {
            var receiver = call.explicitReceiver?.unwrapped() ?: return false
            if (receiver is FirCheckedSafeCallSubject) receiver = receiver.originalReceiverRef.value.unwrapped()
            if (!receiver.resolvedType.isCloseable()) return false
            if (isOwnedBySource(receiver)) return true
            val local = receiver.localSymbol()
            if (local != null) return wrapsBorrowed(locals.getValue(local), visited)
            val inner = unwrapScopeChain(receiver)
            if (inner is FirFunctionCall) return wrapsBorrowed(inner, visited)
            return isRootedAtParameterOrThis(inner)
        }

        /**
         * `process.inputStream`, `connection.errorStream` where the body disconnects `connection`,
         * `socket.getInputStream()` where the body closes `socket`, or a local holding one: a stream its source
         * closes (a process its pipes, a connection or a socket its streams, an archive its entries), so it is
         * not the function's to close.
         */
        private fun isOwnedBySource(expression: FirExpression): Boolean {
            var stream = unwrapScopeChain(expression)
            val held = (stream as? FirPropertyAccessExpression)?.calleeReference?.toResolvedCallableSymbol()?.let { localInitializers[it] }
            if (held != null) stream = unwrapScopeChain(held)
            if (stream is FirSafeCallExpression) stream = stream.selector as? FirExpression ?: return false
            val access = stream as? FirQualifiedAccessExpression ?: return false
            if (access.calleeReference.toResolvedCallableSymbol()?.name?.asString() !in SOURCE_STREAMS) return false
            var source = access.explicitReceiver ?: return false
            if (source is FirCheckedSafeCallSubject) source = source.originalReceiverRef.value
            val sourceType = source.resolvedType
            if (streamOwners.none { sourceType.isSubtypeOf(it) }) return false
            if (sourceType.isSubtypeOf(PROCESS)) return true
            val symbol = source.referencedSymbol() ?: return false
            return symbol in disconnected || symbol in closed || holdsTrackedCreation(symbol)
        }

        /** A local holding a creation this function tracks: the creation carries the obligation, and is reported if nothing settles it. */
        private fun holdsTrackedCreation(local: FirBasedSymbol<*>): Boolean {
            val creation = locals[local] ?: return false
            return resourceType(creation) != null && !wrapsBorrowed(creation, HashSet())
        }

        /** The local, parameter or property this expression reads, through a smart cast or a safe call. */
        private fun FirExpression.referencedSymbol(): FirBasedSymbol<*>? {
            val read = unwrapped().let { if (it is FirCheckedSafeCallSubject) it.originalReceiverRef.value.unwrapped() else it }
            return (read as? FirPropertyAccessExpression)?.calleeReference?.toResolvedCallableSymbol()
        }

        /**
         * `parameterStream`, `this`, `this.stream`, `stream` (a member), `this.holder?.stream`: rooted at `this`
         * through property accesses, or a parameter itself. A property of a parameter (`request.body`)
         * is a stream this function pulled out, so it stays the function's to close.
         */
        private fun isRootedAtParameterOrThis(expression: FirExpression): Boolean {
            var current = expression.unwrapped()
            var throughProperty = false
            while (true) {
                current = when (current) {
                    is FirThisReceiverExpression -> return true
                    is FirCheckedSafeCallSubject -> current.originalReceiverRef.value.unwrapped()
                    is FirSafeCallExpression -> {
                        if (current.selector !is FirPropertyAccessExpression) return false
                        throughProperty = true
                        current.receiver.unwrapped()
                    }
                    is FirPropertyAccessExpression -> {
                        if (current.calleeReference.toResolvedCallableSymbol() is FirValueParameterSymbol) return !throughProperty
                        throughProperty = true
                        (current.explicitReceiver ?: current.dispatchReceiver ?: return false).unwrapped()
                    }
                    else -> return false
                }
            }
        }

        private fun ConeKotlinType.isCloseable(): Boolean = CLOSEABLE.any { isSubtypeOf(it) }

        /** `also { installation = it }` / `apply { field = this }`: the lambda stores the receiver in a property. */
        private fun FirFunctionCall.assignsToProperty(): Boolean {
            var found = false
            accept(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (found) return
                    if (element is FirVariableAssignment) {
                        val target = (element.lValue as? FirPropertyAccessExpression)?.calleeReference?.toResolvedCallableSymbol()
                        if (target is FirPropertySymbol && !target.isLocal) found = true
                    }
                    element.acceptChildren(this)
                }
            })
            return found
        }

        /**
         * Whether the creation is settled by what is above it: the receiver of `use { }` (through scope
         * functions and safe calls), returned, assigned to a property, or an argument of a call.
         */
        private fun isSettledAbove(creation: FirFunctionCall): Boolean {
            var current: FirElement = creation
            for (parent in ancestors.asReversed()) {
                if (parent === current) continue
                when {
                    parent is FirReturnExpression && parent.result.unwrapped() === current -> return true
                    parent is FirVariableAssignment && parent.rValue.unwrapped() === current ->
                        return (parent.lValue as? FirPropertyAccessExpression)?.calleeReference?.toResolvedCallableSymbol()?.let { it is FirPropertySymbol && !it.isLocal } == true
                    parent is FirSafeCallExpression && parent.receiver.unwrapped() === current -> {
                        val selector = parent.selector as? FirFunctionCall ?: return false
                        val name = selector.fqName() ?: return false
                        if (name in USE) return true
                        if (name !in SCOPE_FUNCTIONS && name !in WRAPPERS) return false
                        if (name in SCOPE_FUNCTIONS && selector.assignsToProperty()) return true
                        current = parent
                    }
                    parent is FirFunctionCall && parent.explicitReceiver?.unwrapped() === current -> {
                        val name = parent.fqName() ?: return false
                        if (name in USE) return true
                        if (name !in SCOPE_FUNCTIONS && name !in WRAPPERS) return false
                        if (name in SCOPE_FUNCTIONS && parent.assignsToProperty()) return true
                        current = parent
                    }
                    parent is FirFunctionCall && parent.argumentList.arguments.any { it.unwrapArgument().unwrapped() === current } -> return true
                    parent is FirElvisExpression && parent.lhs.unwrapped() === current -> current = parent
                    parent is FirProperty && parent.initializer?.unwrapped() === current -> return false
                    else -> return false
                }
            }
            return false
        }

        private fun unwrapScopeChain(expression: FirExpression): FirExpression {
            var current = expression.unwrapped()
            while (true) {
                current = when (current) {
                    is FirFunctionCall -> when (current.fqName()) {
                        in SCOPE_FUNCTIONS -> current.explicitReceiver?.unwrapped() ?: return current
                        in RESULT_OF_LAMBDA -> current.lambdaResult() ?: return current
                        else -> return current
                    }
                    is FirSafeCallExpression -> if ((current.selector as? FirFunctionCall)?.fqName() in SCOPE_FUNCTIONS) current.receiver.unwrapped() else return current
                    is FirElvisExpression -> current.lhs.unwrapped()
                    else -> return current
                }
            }
        }

        private fun FirFunctionCall.lambdaResult(): FirExpression? {
            val lambda = argumentList.arguments.lastOrNull()?.unwrapArgument() as? FirAnonymousFunctionExpression ?: return null
            val last = lambda.anonymousFunction.body?.statements?.lastOrNull() ?: return null
            return ((last as? FirReturnExpression)?.result ?: last as? FirExpression)?.unwrapped()
        }

        private fun FirExpression.localSymbol(): FirBasedSymbol<*>? =
            (unwrapped() as? FirPropertyAccessExpression)?.calleeReference?.toResolvedCallableSymbol()?.takeIf { it in locals }

        private fun FirExpression.unwrapped(): FirExpression = (this as? FirSmartCastExpression)?.originalExpression ?: this

        private fun FirFunctionCall.fqName(): String? = calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString()

        private fun ConeKotlinType.isSubtypeOf(classId: ClassId): Boolean {
            val expanded = fullyExpandedType(session)
            val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) ?: return false
            val superType = classId.constructClassLikeType(Array(symbol.typeParameterSymbols.size) { ConeStarProjection }, isMarkedNullable = true)
            return !expanded.isNullableNothing && AbstractTypeChecker.isSubtypeOf(session.typeContext, expanded, superType)
        }
    }
}

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
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
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
 * the function: returned, assigned to a property, or passed as an argument, where whoever
 * receives it is the owner. Everything else is a leak on every path, reported at the creation.
 */
object MustCloseChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    private val CLOSEABLE = listOf(
        ClassId(FqName("kotlin"), org.jetbrains.kotlin.name.Name.identifier("AutoCloseable")),
        ClassId(FqName("java.lang"), org.jetbrains.kotlin.name.Name.identifier("AutoCloseable")),
        ClassId(FqName("java.io"), org.jetbrains.kotlin.name.Name.identifier("Closeable")),
    )
    private val USE = setOf("kotlin.use", "kotlin.io.use", "kotlin.AutoCloseable.use")
    private val SCOPE_FUNCTIONS = setOf("kotlin.apply", "kotlin.also", "kotlin.let", "kotlin.run", "kotlin.takeIf", "kotlin.takeUnless")
    private val RESULT_OF_LAMBDA = USE + setOf("kotlin.let", "kotlin.run", "kotlin.with")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.MUST_CLOSE)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val body = declaration.body ?: return
        val finder = Finder(context.session, config.mustClose.factories.toSet())
        body.accept(finder)
        for ((call, type) in finder.leaked()) {
            val at = call.source ?: continue
            if (at.kind is KtFakeSourceElementKind) continue
            reportKotrail(at, KotrailDiagnostics.RESOURCE_NOT_CLOSED, type)
        }
    }

    /** Finds the resources a body creates and which of them are closed or handed on. */
    private class Finder(private val session: FirSession, private val factories: Set<String>) : FirVisitorVoid() {
        private val created = LinkedHashMap<FirFunctionCall, String>()
        private val settled = HashSet<FirFunctionCall>()
        private val locals = HashMap<FirBasedSymbol<*>, FirFunctionCall>()
        private val settledLocals = HashSet<FirBasedSymbol<*>>()
        private val ancestors = ArrayList<FirElement>()

        fun leaked(): List<Pair<FirFunctionCall, String>> = created.entries
            .filter { (call, _) -> call !in settled && locals.entries.none { (local, creation) -> creation === call && local in settledLocals } }
            .map { (call, type) -> call to type }

        override fun visitElement(element: FirElement) {
            ancestors.add(element)
            element.acceptChildren(this)
            ancestors.removeAt(ancestors.lastIndex)
        }

        override fun visitProperty(property: FirProperty) {
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
            if (type != null) {
                created[functionCall] = type
                if (isSettledAbove(functionCall)) settled += functionCall
            }
            if (functionCall.settlesReceiver()) {
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

        /** `close()`, and `use { }` on a local: the local is settled either way. */
        private fun FirFunctionCall.settlesReceiver(): Boolean {
            val name = calleeReference.toResolvedCallableSymbol()?.name?.asString()
            return (name == "close" && argumentList.arguments.isEmpty()) || fqName() in USE
        }

        /** A constructor of an `AutoCloseable`, or a listed factory: a resource this body owns. */
        private fun resourceType(call: FirFunctionCall): String? {
            val callee = call.calleeReference.toResolvedCallableSymbol() ?: return null
            val isResource = when (callee) {
                is FirConstructorSymbol -> CLOSEABLE.any { call.resolvedType.isSubtypeOf(it) }
                else -> callee.callableId?.asSingleFqName()?.asString() in factories
            }
            return if (isResource) call.resolvedType.renderReadable() else null
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
                        if (name !in SCOPE_FUNCTIONS) return false
                        current = parent
                    }
                    parent is FirFunctionCall && parent.explicitReceiver?.unwrapped() === current -> {
                        val name = parent.fqName() ?: return false
                        if (name in USE) return true
                        if (name !in SCOPE_FUNCTIONS) return false
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

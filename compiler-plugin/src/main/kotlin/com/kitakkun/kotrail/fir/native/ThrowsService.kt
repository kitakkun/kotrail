@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.native

import com.kitakkun.kotrail.compat.qualifierClassId
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirAnonymousObjectExpression
import org.jetbrains.kotlin.fir.expressions.FirCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirThrowExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Per-session analysis of what a function throws for the Objective-C export rule: the first
 * exception its body can let out, found by a `throw`, a precondition (`require`, `check`,
 * `error`, `requireNotNull`, `checkNotNull`), a call to a function that declares `@Throws`, or a
 * call to a function of this module that lets one out itself. A `throw` or call under a `try`
 * whose catches cover it (a catch of `Throwable` or `Exception`, or of the thrown class) does not
 * count; nor does anything in a lambda, except the lambda of an inline function, which runs in
 * place. Results are memoized per symbol.
 */
class ThrowsService(session: FirSession) : FirExtensionSessionComponent(session) {
    /** What a function lets out: a description for the message, and the class name when it is known. */
    class Thrown(val description: String, val className: String?)

    private val cache = HashMap<FirNamedFunctionSymbol, Thrown?>()
    private val visiting = HashSet<FirNamedFunctionSymbol>()

    /** The first exception [symbol] can let out, or null when its body lets none out or is not in this module. */
    fun thrown(symbol: FirNamedFunctionSymbol): Thrown? {
        if (cache.containsKey(symbol)) return cache[symbol]
        if (!visiting.add(symbol)) return null
        try {
            val result = compute(symbol)
            cache[symbol] = result
            return result
        } finally {
            visiting.remove(symbol)
        }
    }

    /** The classes a `@Throws` annotation on [symbol] names, by simple name; null when there is no such annotation. */
    fun declaredThrows(symbol: FirCallableSymbol<*>): List<String>? {
        val annotation = symbol.resolvedAnnotationsWithArguments.firstOrNull { it.toAnnotationClassId(session) in THROWS } ?: return null
        val argument = annotation.findArgumentByName(EXCEPTION_CLASSES, returnFirstWhenNotFound = true) ?: return emptyList()
        val elements: List<FirExpression> = when (argument) {
            is FirVarargArgumentsExpression -> argument.arguments
            is FirCall -> argument.argumentList.arguments
            else -> listOf(argument)
        }
        return elements.mapNotNull { element ->
            val getClass = element.unwrapArgument() as? FirGetClassCall ?: return@mapNotNull null
            val qualifier: FirResolvedQualifier = getClass.argument as? FirResolvedQualifier ?: return@mapNotNull null
            val classId: ClassId = qualifier.qualifierClassId ?: return@mapNotNull null
            classId.shortClassName.asString()
        }
    }

    private fun compute(symbol: FirNamedFunctionSymbol): Thrown? {
        if (!symbol.origin.fromSource) return null
        symbol.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
        val body = symbol.fir.body ?: return null
        val finder = Finder()
        body.accept(finder)
        return finder.found
    }

    private inner class Finder : FirVisitorVoid() {
        var found: Thrown? = null

        /** The catch parameter classes of the enclosing `try` blocks by simple name (null for an unresolved one), innermost last. */
        private val covering = ArrayList<List<String?>>()

        override fun visitElement(element: FirElement) {
            if (found != null) return
            element.acceptChildren(this)
        }

        override fun visitAnonymousFunctionExpression(anonymousFunctionExpression: FirAnonymousFunctionExpression) {}

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {}

        override fun visitAnonymousObjectExpression(anonymousObjectExpression: FirAnonymousObjectExpression) {}

        override fun visitNamedFunction(namedFunction: FirNamedFunction) {}

        override fun visitTryExpression(tryExpression: FirTryExpression) {
            covering += tryExpression.catches.map { it.parameter.returnTypeRef.coneType.fullyExpandedType(session).classId?.shortClassName?.asString() }
            tryExpression.tryBlock.accept(this)
            covering.removeAt(covering.lastIndex)
            tryExpression.catches.forEach { it.block.accept(this) }
            tryExpression.finallyBlock?.accept(this)
        }

        override fun visitThrowExpression(throwExpression: FirThrowExpression) {
            val name = throwExpression.exception.resolvedType.fullyExpandedType(session).classId?.shortClassName?.asString()
            // `if (e is CancellationException) throw e` in a catch clause passes cancellation on; a suspend caller expects it.
            if (name != "CancellationException") record(name, Thrown("${name ?: "an exception"} (thrown here)", name))
            visitElement(throwExpression)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            val callee = functionCall.calleeReference.toResolvedNamedFunctionSymbol()
            if (callee != null) {
                val fqName = callee.callableId?.asSingleFqName()?.asString()
                val precondition = PRECONDITIONS[fqName]
                val declared = declaredThrows(callee)
                when {
                    precondition != null -> record(precondition.first, Thrown("${precondition.first} (${callee.name})", precondition.first))
                    declared != null -> {
                        val first = declared.firstOrNull()
                        val description = if (first != null) "$first (declared by '${callee.name}')" else "what '${callee.name}' declares"
                        record(first, Thrown(description, first))
                    }
                    callee.origin.fromSource -> thrown(callee)?.let { inner ->
                        record(inner.className, Thrown("${inner.className ?: "an exception"} (through '${callee.name}')", inner.className))
                    }
                }
            }
            functionCall.explicitReceiver?.accept(this)
            val mapping = functionCall.resolvedArgumentMapping
            for (argument in functionCall.argumentList.arguments) {
                val unwrapped = argument.unwrapArgument()
                if (unwrapped is FirAnonymousFunctionExpression) {
                    // Only the lambda of an inline function runs here; any other runs later, behind its own boundary.
                    val parameter = mapping?.get(unwrapped) ?: mapping?.get(argument)
                    if (callee != null && callee.isInline && parameter?.isNoinline != true) unwrapped.anonymousFunction.body?.accept(this)
                } else {
                    argument.accept(this)
                }
            }
        }

        /** Records [thrown] unless an enclosing `try` catches the class named [className] (or everything). */
        private fun record(className: String?, thrown: Thrown) {
            if (found != null) return
            val caught = covering.any { catches -> catches.any { it == null || it in CATCH_ALL || (className != null && it == className) } }
            if (!caught) found = thrown
        }
    }

    companion object {
        val THROWS = setOf(
            ClassId(FqName("kotlin"), Name.identifier("Throws")),
            ClassId(FqName("kotlin.jvm"), Name.identifier("Throws")),
        )
        private val EXCEPTION_CLASSES = Name.identifier("exceptionClasses")
        private val CATCH_ALL = setOf("Throwable", "Exception")
        private const val IAE = "IllegalArgumentException"
        private const val ISE = "IllegalStateException"
        private val PRECONDITIONS: Map<String, Pair<String, String>> = mapOf(
            "kotlin.require" to (IAE to "require"),
            "kotlin.requireNotNull" to (IAE to "requireNotNull"),
            "kotlin.check" to (ISE to "check"),
            "kotlin.checkNotNull" to (ISE to "checkNotNull"),
            "kotlin.error" to (ISE to "error"),
        )
    }
}

val FirSession.throwsService: ThrowsService by FirSession.sessionComponentAccessor()

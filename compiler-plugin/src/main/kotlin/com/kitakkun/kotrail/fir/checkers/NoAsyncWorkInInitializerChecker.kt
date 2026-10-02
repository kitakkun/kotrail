package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.exclude.Glob
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.test.isTestSourcePath
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.InlineStatus
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirCheckNotNullCall
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedDelegateExpression
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Keeps asynchronous work out of property initializers, where it starts as a side effect of
 * building the object:
 *
 * ```kotlin
 * private val requests = Channel<Request>(Channel.UNLIMITED).also { requests ->
 *     scope.launch { for (request in requests) handle(request) }    // reported
 * }
 * val job = scope.launch { refresh() }                               // fine: the Job is the value
 * init { scope.launch { consume(requests) } }                        // fine: visible construction code
 * ```
 *
 * A reader of the property sees a channel and never learns that a consumer loop runs behind
 * it. The work starts during construction, before the properties below it are initialized and
 * before the owner decided anything; nothing keeps its handle, so nothing can stop it or see it
 * fail; a test that only constructs the object starts it.
 *
 * Inspected are member and top-level properties: the initializer, an explicit backing field's
 * initializer, and the delegate expression, where `by lazy { }` counts its lambda as the
 * initializer. Inside, a call to one of `noAsyncWorkInInitializer.functions` is reported, also
 * through the lambdas an inline function runs in place (`also`, `apply`, `let`, `buildList`). A
 * lambda that runs later (a non-inline, `noinline` or `crossinline` one, a local function, an
 * object's members) is not looked into. A call whose result is the property's value is not
 * reported: the type says that work begins.
 */
object NoAsyncWorkInInitializerChecker : FirPropertyChecker(MppCheckerKind.Common) {
    private const val LAZY = "kotlin.lazy"

    /** Scope functions that return their receiver: a start in the receiver is still the value. */
    private val RETURNS_RECEIVER = setOf("kotlin.also", "kotlin.apply")

    /** Scope functions that return what their lambda returns. */
    private val RETURNS_LAMBDA_RESULT = setOf("kotlin.let", "kotlin.run", "kotlin.with")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.NO_ASYNC_WORK_IN_INITIALIZER)) return
        // A local's initializer runs when the code around it runs, which its reader sees.
        if (declaration.isLocal) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val path = context.containingFileSymbol?.sourceFile?.path
        if (path != null && isTestSourcePath(path)) return
        val owner = ownerName(declaration) ?: return
        val functions = config.noAsyncWorkInInitializer.functions.map(::Glob)

        val roots = mutableListOf<FirExpression>()
        declaration.initializer?.let(roots::add)
        declaration.backingField?.initializer?.let(roots::add)
        val delegate = declaration.delegate?.let { (it as? FirWrappedDelegateExpression)?.expression ?: it }
        if (delegate != null) roots += delegate.lazyInitializer() ?: delegate

        val starts = LinkedHashMap<FirFunctionCall, String>()
        for (root in roots) {
            val values = HashSet<FirStatement>().also { collectValues(root, it) }
            root.accept(StartFinder(functions, values, starts))
        }
        val property = declaration.name.asString()
        for ((call, starter) in starts) {
            reportKotrail(call.source, KotrailDiagnostics.ASYNC_WORK_IN_INITIALIZER, property, starter, owner)
        }
    }

    /** The class the property belongs to, as it is written in its file, or the file of a top-level one; null for an anonymous object's member. */
    context(context: CheckerContext)
    private fun ownerName(declaration: FirProperty): String? {
        val dispatch = declaration.dispatchReceiverType ?: return context.containingFileSymbol?.sourceFile?.name
        val classId = dispatch.classId ?: return null
        if (classId.shortClassName.isSpecial) return null
        return classId.relativeClassName.asString()
    }

    /** The body of the lambda handed to `lazy`, which is what runs on first access; null for any other delegate. */
    private fun FirExpression.lazyInitializer(): FirBlock? {
        val call = this as? FirFunctionCall ?: return null
        if (call.calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString() != LAZY) return null
        val lambda = call.argumentList.arguments.lastOrNull()?.unwrapArgument() as? FirAnonymousFunctionExpression ?: return null
        return lambda.anonymousFunction.body
    }

    /**
     * Collects the expressions whose result is the value of [statement]: itself, the selector of a
     * safe call, the operand of `!!`, the branches of `if`, `when`, `try` and `?:`, the last
     * statement of a block, the receiver of `also` and `apply`, the lambda result of `let`, `run`
     * and `with`.
     */
    private fun collectValues(statement: FirStatement, into: MutableSet<FirStatement>) {
        if (!into.add(statement)) return
        when (statement) {
            is FirReturnExpression -> collectValues(statement.result, into)
            is FirSmartCastExpression -> collectValues(statement.originalExpression, into)
            is FirSafeCallExpression -> collectValues(statement.selector, into)
            is FirCheckNotNullCall -> statement.argumentList.arguments.firstOrNull()?.let { collectValues(it, into) }
            is FirBlock -> statement.statements.lastOrNull()?.let { collectValues(it, into) }
            is FirWhenExpression -> statement.branches.forEach { collectValues(it.result, into) }
            is FirTryExpression -> {
                collectValues(statement.tryBlock, into)
                statement.catches.forEach { collectValues(it.block, into) }
            }
            is FirElvisExpression -> {
                collectValues(statement.lhs, into)
                collectValues(statement.rhs, into)
            }
            is FirFunctionCall -> {
                val callee = statement.calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString()
                when (callee) {
                    in RETURNS_RECEIVER -> statement.explicitReceiver?.let { collectValues(it, into) }
                    in RETURNS_LAMBDA_RESULT -> {
                        val lambda = statement.argumentList.arguments.lastOrNull()?.unwrapArgument() as? FirAnonymousFunctionExpression
                        lambda?.anonymousFunction?.body?.let { collectValues(it, into) }
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * Walks what runs while the initializer is evaluated and records each call that starts work,
     * with the name of the function called, unless it is one of [values].
     */
    private class StartFinder(
        private val functions: List<Glob>,
        private val values: Set<FirStatement>,
        private val starts: MutableMap<FirFunctionCall, String>,
    ) : FirVisitorVoid() {
        override fun visitElement(element: FirElement) {
            when (element) {
                // A lambda an inline function runs in place; any other runs when it is invoked, later.
                is FirAnonymousFunction -> if (element.inlineStatus == InlineStatus.Inline) element.body?.accept(this)
                // A local function, an accessor, an object or a local class: their code runs when called.
                is FirFunction, is FirClass -> {}
                else -> {
                    if (element is FirFunctionCall && element !in values) {
                        element.starter()?.let { starts[element] = it }
                    }
                    element.acceptChildren(this)
                }
            }
        }

        private fun FirFunctionCall.starter(): String? {
            val callee = calleeReference.toResolvedCallableSymbol() ?: return null
            val name = callee.callableId?.asSingleFqName()?.asString() ?: return null
            if (functions.none { it.matches(name) }) return null
            return callee.name.asString()
        }
    }
}

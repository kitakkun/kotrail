package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.FixEdit
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.fix.FixBuilder
import com.kitakkun.kotrail.fir.isValueUsed
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.test.isTestSourcePath
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.InlineStatus
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirCheckedSafeCallSubject
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenBranch
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.impl.FirSingleExpressionBlock
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedPropertySymbol
import org.jetbrains.kotlin.fir.references.toResolvedValueParameterSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousInitializerSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Reports the get-and-set idiom written through a scope function: a call whose receiver is a
 * variable that the call's own block assigns, so that the old value comes out while the new one
 * goes in.
 *
 * ```kotlin
 * val replaced = synchronized(lock) {
 *     ready.also {              // reported: returns the previous ready while the block overwrites ready
 *         ready = frame
 *         frameCounter++
 *     }
 * }
 * replaced?.close()
 *
 * result.also { log(it) }       // fine: the block does not assign result
 * holder.value.also { holder.value = v }   // not looked at: read through another object
 * ```
 *
 * A reader sees `ready ... ready = frame` and misses that the previous value is what the
 * expression yields; dropping the `.also` then looks harmless and loses that value. The receiver
 * counts when it is a local variable or a property read on `this` or at top level (smart casts
 * and safe calls included), and the assignment counts at any depth that runs before the call
 * returns: the block itself and the in-place lambdas inside it, not a lambda stored for later,
 * a local function or a local class. A call whose value nobody consumes is left alone: with the
 * value dropped, nothing old comes out, and the block reads as using the variable and then
 * replacing it. Test source sets are not looked at. When the block reads neither its parameter nor
 * its receiver, the message says so: that unused value is the tell that the call is there only for
 * the old value.
 *
 * The fix is recorded for a function that returns its receiver (`also`, `apply`) whose trailing
 * lambda reads neither its parameter nor its receiver and declares nothing at its top level, when
 * the call is the initializer of a local, the operand of a `return`, or the last expression of a
 * lambda or of a braced `if`/`when` branch whose value is used, and that statement has its line to
 * itself. It reads the variable first and lets the block's statements follow on their own lines.
 */
object NoSwapThroughAlsoChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    private const val PREVIOUS = "previous"
    private val PREVIOUS_NAME = Regex("\\b$PREVIOUS\\b")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.NO_SWAP_THROUGH_ALSO)) return
        val source = expression.source ?: return
        // FIR gives a safe call's selector the whole `x?.f { }` as a fake source; every other fake
        // call is synthesized.
        val kind = source.kind
        if (kind is KtFakeSourceElementKind && kind != KtFakeSourceElementKind.DesugaredSafeCallExpression) return
        val callee = expression.calleeReference.toResolvedCallableSymbol() as? FirNamedFunctionSymbol ?: return
        if (callee.callableId.asSingleFqName().asString() !in config.noSwapThroughAlso.functions) return
        val receiver = expression.explicitReceiver?.unwrapReceiver() ?: return
        val variable = receiver.asVariable() ?: return
        val lambdas = expression.arguments.mapNotNull { (it.unwrapArgument() as? FirAnonymousFunctionExpression)?.anonymousFunction }
            .filter { it.runsInPlace() }
        if (lambdas.none { it.assigns(variable) }) return
        if (!isValueUsed(context.containingElements)) return
        val path = context.containingFileSymbol?.sourceFile?.path
        if (path != null && isTestSourcePath(path)) return

        val returnsReceiver = callee.returnsReceiver()
        val name = callee.name.asString()
        val action = if (returnsReceiver) "$name returns" else "$name hands its block"
        val lambda = lambdas.singleOrNull()
        val uses = lambda?.uses()
        val rest = when {
            uses == null || uses.readsValue -> ": the old value comes out and the new one goes in, in one expression that reads as neither"
            returnsReceiver -> "; the block never uses it, so the $name only keeps the old value"
            else -> "; the block never uses it, so the $name takes the old value only to drop it"
        }
        val fix = if (returnsReceiver && lambda != null && uses != null) fixFor(expression, receiver, lambda, uses) else emptyList()
        reportKotrail(source, KotrailDiagnostics.SWAP_THROUGH_ALSO, action, variable.symbol.name.asString(), rest, fix)
    }

    /** A variable as one place in memory: its symbol and, for a member or an extension, the `this` it is read on. */
    private data class Variable(
        val symbol: FirPropertySymbol,
        val dispatchOwner: FirBasedSymbol<*>?,
        val extensionOwner: FirBasedSymbol<*>?,
    )

    /**
     * The variable this expression reads or assigns: a local, a top-level property, or a property
     * of `this` (written or implicit). A property read through any other receiver is another
     * object's state, and `null`.
     */
    private fun FirExpression.asVariable(): Variable? {
        val access = this as? FirQualifiedAccessExpression ?: return null
        val symbol = access.calleeReference.toResolvedPropertySymbol() ?: return null
        val explicit = access.explicitReceiver?.unwrapReceiver()
        if (explicit != null && explicit !is FirThisReceiverExpression) return null
        val dispatch = access.dispatchReceiver?.unwrapReceiver()
        val extension = access.extensionReceiver?.unwrapReceiver()
        if (dispatch != null && dispatch !is FirThisReceiverExpression) return null
        if (extension != null && extension !is FirThisReceiverExpression) return null
        return Variable(
            symbol = symbol,
            dispatchOwner = dispatch?.calleeReference?.boundSymbol,
            extensionOwner = extension?.calleeReference?.boundSymbol,
        )
    }

    private fun FirExpression.unwrapReceiver(): FirExpression = when (this) {
        is FirSmartCastExpression -> originalExpression.unwrapReceiver()
        is FirCheckedSafeCallSubject -> originalReceiverRef.value.unwrapReceiver()
        else -> this
    }

    /** An inline lambda, or one whose callee promises by contract to call it before returning. */
    private fun FirAnonymousFunction.runsInPlace(): Boolean = inlineStatus == InlineStatus.Inline || invocationKind != null

    private fun FirAnonymousFunction.assigns(variable: Variable): Boolean {
        val finder = AssignmentFinder(variable)
        body?.accept(finder)
        return finder.found
    }

    /** Finds an assignment to [target] in code that runs while the block does. */
    private class AssignmentFinder(private val target: Variable) : FirVisitorVoid() {
        var found = false

        override fun visitElement(element: FirElement) {
            if (found) return
            when (element) {
                is FirAnonymousFunction -> if (element.runsInPlace()) element.acceptChildren(this)
                is FirFunction, is FirClass -> Unit
                else -> element.acceptChildren(this)
            }
        }

        override fun visitVariableAssignment(variableAssignment: FirVariableAssignment) {
            if (found) return
            // `x += 1` and `x++` assign through a reference to the original access.
            val lValue = when (val value = variableAssignment.lValue) {
                is FirDesugaredAssignmentValueReferenceExpression -> value.expressionRef.value
                else -> value
            }
            if (lValue.asVariable() == target) {
                found = true
                return
            }
            variableAssignment.acceptChildren(this)
        }
    }

    /** `T.f(...): T`: the call yields its receiver, as `also` and `apply` do. */
    private fun FirNamedFunctionSymbol.returnsReceiver(): Boolean {
        val receiver = resolvedReceiverType as? ConeTypeParameterType ?: return false
        val returned = resolvedReturnType as? ConeTypeParameterType ?: return false
        return receiver.lookupTag == returned.lookupTag
    }

    /**
     * The edit that reads the variable first and lets the block's statements follow:
     *
     * - `val y = x.also { ... }` becomes `val y = x` and the statements;
     * - `return x.also { ... }` becomes `val previous = x`, the statements and `return previous`;
     * - `x.also { ... }` as the last expression of a lambda whose value is used, or of a braced
     *   `if`/`when` branch, becomes `val previous = x`, the statements and `previous`.
     *
     * Empty for any other place, and whenever moving the statements out of the lambda could change
     * what they mean or where they run.
     */
    context(context: CheckerContext)
    private fun fixFor(call: FirFunctionCall, receiver: FirExpression, lambda: FirAnonymousFunction, uses: LambdaUses): List<FixEdit> {
        if (lambda.hasExplicitParameterList) return emptyList()
        val statements = lambda.body?.statements ?: return emptyList()
        if (statements.any { it is FirDeclaration }) return emptyList()
        if (uses.readsValue || uses.returnsToLambda) return emptyList()
        val callSource = call.source ?: return emptyList()
        // A fake source here is a safe call's selector, whose block runs only for a non-null
        // receiver; moved out, the statements would always run.
        if (callSource.kind is KtFakeSourceElementKind) return emptyList()
        val receiverText = writtenReceiver(call, callSource, receiver, lambda) ?: return emptyList()
        val lines = FixBuilder.textOf(lambda.source)?.let(::blockLines) ?: return emptyList()
        val outermost = context.containingDeclarations.firstOrNull { it is FirCallableSymbol<*> || it is FirAnonymousInitializerSymbol }
        val builder = FixBuilder.over(outermost?.source) ?: return emptyList()

        // containingElements ends with the checked call itself.
        val elements = context.containingElements
        val parent = elements.getOrNull(elements.size - 2)
        val grandparent = elements.getOrNull(elements.size - 3)
        return when {
            parent is FirProperty && parent.isLocal && parent.initializer === call && grandparent is FirBlock -> {
                val statement = parent.source ?: return emptyList()
                val indent = lineIndent(builder, statement, callSource) ?: return emptyList()
                listOf(builder.replaceRange(callSource.startOffset, callSource.endOffset, receiverText + lines.under(indent)))
            }
            parent is FirReturnExpression && parent.result === call && grandparent is FirBlock -> {
                val returnSource = parent.source ?: return emptyList()
                if (PREVIOUS_NAME.containsMatchIn(builder.text)) return emptyList()
                // FIR adds this implicit return to a lambda's last expression only when the
                // lambda's return type is not Unit, that is, when its value is used.
                if (returnSource.kind == KtFakeSourceElementKind.ImplicitReturn.FromLastStatement) {
                    if (elements.getOrNull(elements.size - 4) !is FirAnonymousFunction) return emptyList()
                    val indent = lineIndent(builder, callSource, callSource) ?: return emptyList()
                    val replacement = "val $PREVIOUS = $receiverText${lines.under(indent)}\n$indent$PREVIOUS"
                    return listOf(builder.replaceRange(callSource.startOffset, callSource.endOffset, replacement))
                }
                if (returnSource.kind is KtFakeSourceElementKind) return emptyList()
                val indent = lineIndent(builder, returnSource, callSource) ?: return emptyList()
                val keyword = FixBuilder.textOf(returnSource)?.take(callSource.startOffset - returnSource.startOffset)?.trim()
                    ?: return emptyList()
                val replacement = "val $PREVIOUS = $receiverText${lines.under(indent)}\n$indent$keyword $PREVIOUS"
                listOf(builder.replaceRange(returnSource.startOffset, callSource.endOffset, replacement))
            }
            parent is FirBlock && grandparent is FirWhenBranch && grandparent.result === parent && parent.statements.lastOrNull() === call -> {
                if (parent is FirSingleExpressionBlock || FixBuilder.textOf(parent.source)?.startsWith("{") != true) return emptyList()
                if (PREVIOUS_NAME.containsMatchIn(builder.text)) return emptyList()
                val indent = lineIndent(builder, callSource, callSource) ?: return emptyList()
                val replacement = "val $PREVIOUS = $receiverText${lines.under(indent)}\n$indent$PREVIOUS"
                listOf(builder.replaceRange(callSource.startOffset, callSource.endOffset, replacement))
            }
            else -> emptyList()
        }
    }

    /**
     * The receiver as written, when the call is written `receiver.f { ... }`: a plain dot, the
     * lambda trailing right after the name, with no label and no parentheses. Otherwise `null`.
     */
    private fun writtenReceiver(call: FirFunctionCall, callSource: KtSourceElement, receiver: FirExpression, lambda: FirAnonymousFunction): String? {
        val callText = FixBuilder.textOf(callSource) ?: return null
        val receiverSource = call.explicitReceiver?.source ?: receiver.source ?: return null
        val lambdaSource = lambda.source ?: return null
        if (receiverSource.startOffset != callSource.startOffset || lambdaSource.endOffset != callSource.endOffset) return null
        val receiverEnd = receiverSource.endOffset - callSource.startOffset
        val lambdaStart = lambdaSource.startOffset - callSource.startOffset
        if (receiverEnd !in 0..lambdaStart) return null
        val name = (call.calleeReference.toResolvedCallableSymbol() as? FirNamedFunctionSymbol)?.name?.asString() ?: return null
        if (callText.substring(receiverEnd, lambdaStart).filterNot { it.isWhitespace() } != ".$name") return null
        return callText.substring(0, receiverEnd)
    }

    /**
     * The statements of a block written `{ statement }` on one line, or with its braces on lines
     * of their own, the common indentation taken off. `null` for any other layout, and for a raw
     * string, whose content re-indenting would change.
     */
    private fun blockLines(lambdaText: String): List<String>? {
        if (!lambdaText.startsWith("{") || !lambdaText.endsWith("}")) return null
        val inner = lambdaText.substring(1, lambdaText.length - 1)
        if ("\"\"\"" in inner || '\r' in inner) return null
        if ('\n' !in inner) return inner.trim().takeIf { it.isNotEmpty() }?.let(::listOf)
        val lines = inner.split('\n')
        if (lines.first().isNotBlank() || lines.last().isNotBlank()) return null
        val body = lines.subList(1, lines.size - 1)
        val margin = body.filter { it.isNotBlank() }.map { line -> line.takeWhile { it.isWhitespace() } }
            .reduceOrNull { a, b -> a.commonPrefixWith(b) } ?: return null
        return body.map { if (it.isBlank()) "" else it.removePrefix(margin).trimEnd() }
    }

    /** Each line on a line of its own below the statement, at [indent]. */
    private fun List<String>.under(indent: String): String = joinToString("") { if (it.isEmpty()) "\n" else "\n$indent$it" }

    /**
     * The indentation of [statement] when it opens its line and nothing but whitespace follows
     * [end] on the line it ends; `null` otherwise, since the statements could not take their own
     * lines.
     */
    private fun lineIndent(builder: FixBuilder, statement: KtSourceElement, end: KtSourceElement): String? {
        val start = builder.indentStart(statement.startOffset)
        if (builder.charAt(start - 1) != '\n') return null
        var offset = end.endOffset
        while (true) {
            val char = builder.charAt(offset) ?: return null
            if (char == '\n') break
            if (!char.isWhitespace()) return null
            offset++
        }
        return builder.indentAt(statement.startOffset)
    }

    /** What a lambda's body does with the value it is given, and whether it leaves the lambda by its label. */
    private data class LambdaUses(
        /** The body reads the lambda's parameter or its receiver, written (`it`, `this`) or implicit (a member call). */
        val readsValue: Boolean,
        /** The body returns to the lambda itself, as `return@also`. */
        val returnsToLambda: Boolean,
    )

    private fun FirAnonymousFunction.uses(): LambdaUses {
        val finder = LambdaUseFinder(this)
        body?.accept(finder)
        return LambdaUses(readsValue = finder.readsValue, returnsToLambda = finder.returnsToLambda)
    }

    /**
     * Finds a use of the lambda's parameter or receiver and a return to the lambda itself. A call
     * or property access counts as reading the receiver only when the receiver it resolves to is
     * the lambda's: inside `ready.run { ... }`, `ready = frame` assigns the enclosing class's
     * property and does not read the lambda's receiver.
     */
    private class LambdaUseFinder(private val lambda: FirAnonymousFunction) : FirVisitorVoid() {
        var readsValue = false
        var returnsToLambda = false
        private val parameters = lambda.valueParameters.map { it.symbol }.toSet()
        private val receiver = lambda.receiverParameter?.symbol

        override fun visitElement(element: FirElement) {
            if (readsValue && returnsToLambda) return
            element.acceptChildren(this)
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            if (propertyAccessExpression.calleeReference.toResolvedValueParameterSymbol() in parameters) readsValue = true
            visitElement(propertyAccessExpression)
        }

        override fun visitThisReceiverExpression(thisReceiverExpression: FirThisReceiverExpression) {
            if (receiver != null && thisReceiverExpression.calleeReference.boundSymbol == receiver) readsValue = true
        }

        override fun visitReturnExpression(returnExpression: FirReturnExpression) {
            if (returnExpression.target.labeledElement === lambda && returnExpression.source?.kind !is KtFakeSourceElementKind) {
                returnsToLambda = true
            }
            visitElement(returnExpression)
        }
    }
}

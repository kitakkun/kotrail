@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.ComplexityRecords
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.compose.isPreview
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.isNothing
import org.jetbrains.kotlin.fir.types.isNullableNothing
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.isSuspendOrKSuspendFunctionType
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.receiverType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.returnType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * Scores what a reader has to hold in mind to follow a composable, and reports the ones over
 * `maxScore`, naming the part of the body where the points pile up:
 *
 * | What | Points |
 * |---|---|
 * | A state source: a call that produces a `State` (`remember { mutableStateOf(...) }`, `rememberSaveable`, `derivedStateOf`, `collectAsState`), or a call listed in `stateFactories`; not `rememberUpdatedState` | 1 each |
 * | An effect (`LaunchedEffect`, `DisposableEffect`, `produceState`, `SideEffect`) | 1, +1 when it writes a `State`; its body's branches count as branches |
 * | A `launch` / `async` from a handler | 1 each |
 * | A branch (`if`, a `when` case, `?:` with a computed fallback), a boolean `&&` / `||` in such a condition, a loop | 1 each; an `if` / `when` whose every result is a constant or a reference (`colors.hover`, `theme.colors.idle`: a property chain with no call in it) is a lookup and scores 1 in all, and a fallback that is one scores nothing |
 * | A `CompositionLocal.current` read | 1 each |
 * | A callback parameter beyond four | 1 each, at most 4 |
 *
 * Neither line count nor call nesting counts: [FunctionLengthChecker] and [ComposableNestingChecker]
 * own those. Every lambda handed to a composable (`Column { }`, `items { }`), every event handler
 * lambda (`onClick = { }`, `Modifier.clickable { }`), every effect and every branch is a subtree
 * with a score of its own, so that a composable over the limit is told which block to extract (the
 * innermost subtree carrying at least `hotspotShare` percent of the total) and what kind of
 * extraction fits it, or, when the points are spread, which kinds dominate. A
 * block that spans nearly the whole body (at least [WHOLE_BODY_SHARE] percent of its source) is
 * the root layout, not something to extract, and is never named; the search goes on inside it.
 *
 * A handler is a lambda argument whose parameter is a plain function type returning `Unit` (or
 * `Nothing`): not `@Composable` (a content slot), not `suspend` (a coroutine body), without a
 * receiver (a builder), and not run in place by an inline function. Lambdas of effects, state
 * producers and the gesture and drawing callees in [NOT_HANDLER_CALLEES] are not handlers. A
 * handler scores what its body holds like any other block; being a block of its own, it can be
 * named as the hotspot, with the advice to give it a name.
 * A composable within the limit that still has one block carrying `hotspotMin` points on its own
 * gets that block reported as a standalone hotspot. Every composable's score and breakdown is
 * also written to a record for the `kotrailComplexity` report, under or over the limit.
 */
object ComposableComplexityChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    private val EFFECTS = setOf("LaunchedEffect", "DisposableEffect", "produceState", "SideEffect")
    private val LAUNCHERS = setOf("launch", "async")
    private val LOOP_CALLS = setOf("forEach", "forEachIndexed", "map", "items", "itemsIndexed", "repeat", "fastForEach")
    /** Produces a `State` but holds a fresh callback rather than UI state: the freshness idiom, not a source of change. */
    private val NOT_STATE_SOURCES = setOf("rememberUpdatedState")
    /**
     * Callees whose lambdas are not event handlers: effects and state producers run the lambda on
     * their own terms, and gesture and drawing callees take code that is part of the input or
     * drawing pipeline. A trailing `*` matches a prefix.
     */
    private val NOT_HANDLER_CALLEES = listOf(
        "LaunchedEffect", "DisposableEffect", "SideEffect", "produceState",
        "remember", "rememberSaveable", "derivedStateOf", "snapshotFlow", "launch", "async",
        "pointerInput", "awaitEachGesture", "awaitPointerEventScope", "detect*", "await*",
        "drawBehind", "drawWithContent", "drawWithCache", "Canvas",
    )
    private val STATE = ClassId(FqName("androidx.compose.runtime"), Name.identifier("State"))
    private const val FREE_CALLBACKS = 4
    private const val MAX_CALLBACK_POINTS = 4
    private const val HOTSPOT_MIN = 5
    /** A kind that carries at least this share of the total is named as dominating a spread score. */
    private const val DOMINANT_SHARE = 30
    /** A block covering at least this share of the body's source is the body itself, not a block to extract. */
    private const val WHOLE_BODY_SHARE = 90

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_COMPLEXITY)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val session = context.session
        if (!declaration.symbol.isComposable(session) || declaration.symbol.isPreview(session)) return
        val body = declaration.body ?: return
        val settings = config.compose.complexity

        val scorer = Scorer(session, settings.stateFactories.toSet())
        val callbacks = declaration.valueParameters.count { it.returnTypeRef.coneType.isSomeFunctionType(session) }
        scorer.root.add(Kind.COUPLING, (callbacks - FREE_CALLBACKS).coerceIn(0, MAX_CALLBACK_POINTS))
        body.accept(scorer)
        val root = scorer.root
        val total = root.total()
        val bodySource = body.source
        val hotspot = scorer.hotspot(total, settings.hotspotShare, bodySource)

        config.complexityDir?.let { directory ->
            val path = context.containingFileSymbol?.sourceFile?.path
            if (path != null) ComplexityRecords.record(directory, path, declaration, total, root, hotspot)
        }
        if (settings.maxScore <= 0) return
        if (total <= settings.maxScore) {
            // Within the limit, one block can still carry enough on its own to be worth taking out.
            if (settings.hotspotMin <= 0) return
            val standalone = scorer.innermost(bodySource) { it.total() >= settings.hotspotMin } ?: return
            reportKotrail(standalone.source, KotrailDiagnostics.COMPOSABLE_HOTSPOT_STANDALONE, standalone.total().toString(), standalone.extraction())
            return
        }

        val advice = if (hotspot != null) {
            "'${hotspot.label}' carries ${hotspot.total()} of them: ${hotspot.extraction()}"
        } else {
            val byKind = root.byKind().filterValues { it > 0 }.entries.sortedByDescending { it.value }
            val dominant = byKind.filter { it.value * 100 >= total * DOMINANT_SHARE }.ifEmpty { byKind.take(1) }
            val named = dominant.joinToString(" and ") { (kind, points) -> "${kind.label} ($points)" }
            val verb = if (dominant.size == 1 && dominant.first().key == Kind.COUPLING) "dominates" else "dominate"
            val returnsValue = !declaration.returnTypeRef.coneType.isUnit
            "the points are spread across the body with no single block to extract; $named $verb: ${root.adviceFor(dominant.first().key, returnsValue, bodySource)}"
        }
        reportKotrail(source, KotrailDiagnostics.COMPOSABLE_TOO_COMPLEX, "$total (${root.breakdown()}), limit ${settings.maxScore}", advice)
        if (hotspot != null) {
            reportKotrail(hotspot.source, KotrailDiagnostics.COMPOSABLE_COMPLEXITY_HOTSPOT, hotspot.total().toString(), total.toString())
        }
    }

    enum class Kind(val label: String, val advice: String) {
        STATES("states", "move them into a state holder the composable receives"),
        EFFECTS("effects", "move the work behind them into a view model or a holder"),
        BRANCHES("branches", "give each variant of the UI a composable of its own"),
        COUPLING("coupling", "fold the callbacks into an actions interface, or take fewer locals"),
    }

    /** What a block is: decides the advice for extracting it. */
    enum class Block { COMPOSABLE_LAMBDA, REMEMBER, EFFECT, BRANCH, HANDLER }

    /** The points of one subtree: what it scored itself, and the subtrees below it. */
    class Node(val label: String, val source: KtSourceElement?, private val block: Block) {
        private val own = IntArray(Kind.entries.size)
        val children = ArrayList<Node>()

        fun add(kind: Kind, points: Int) {
            own[kind.ordinal] += points
        }

        fun total(): Int = own.sum() + children.sumOf { it.total() }

        fun byKind(): Map<Kind, Int> = Kind.entries.associateWith { kind -> own[kind.ordinal] + children.sumOf { it.byKind()[kind] ?: 0 } }

        fun breakdown(): String = byKind().filterValues { it > 0 }.entries.joinToString(", ") { (kind, points) -> "${kind.label} $points" }

        /**
         * The advice when [kind] dominates a spread score. Branches get the advice of the block
         * that holds most of them when one does and it is not the whole body ([body]), or a plain
         * function when the composable computes a value rather than emitting UI.
         */
        fun adviceFor(kind: Kind, returnsValue: Boolean, body: KtSourceElement?): String {
            if (kind != Kind.BRANCHES) return kind.advice
            if (returnsValue) return "move the mapping into a plain function"
            val branches = byKind()[Kind.BRANCHES] ?: 0
            val holder = children.filterNot { it.spans(body) }.maxByOrNull { it.byKind()[Kind.BRANCHES] ?: 0 }
            val held = holder?.byKind()?.get(Kind.BRANCHES) ?: 0
            return if (holder != null && held * 2 >= branches) "most of them sit in '${holder.label}', ${holder.extraction()}" else kind.advice
        }

        /** Whether this block covers at least [WHOLE_BODY_SHARE] percent of [body]'s source: the root layout, not a block to extract. */
        fun spans(body: KtSourceElement?): Boolean {
            val own = source ?: return false
            if (body == null) return false
            val length = body.endOffset - body.startOffset
            val covered = minOf(own.endOffset, body.endOffset) - maxOf(own.startOffset, body.startOffset)
            return length > 0 && covered * 100 >= length * WHOLE_BODY_SHARE
        }

        /** How to take this block out of the composable. */
        fun extraction(): String = when (block) {
            Block.COMPOSABLE_LAMBDA -> "extract it into a composable of its own"
            Block.REMEMBER -> "move the computation into a plain function"
            Block.EFFECT -> "move its body into a non-composable handler or an effect helper of its own"
            Block.BRANCH -> "give this variant of the UI a composable of its own"
            Block.HANDLER -> "give it a name: move the body into a function and pass a reference"
        }
    }

    /** Walks a body, scoring into the current [Node]; a lambda handed to a composable, an event handler lambda, an effect and a branch open a child. */
    private class Scorer(private val session: FirSession, private val stateFactories: Set<String>) : FirVisitorVoid() {
        val root = Node("body", null, Block.COMPOSABLE_LAMBDA)
        private var current = root
        /** Inside a call that already scored as a state source: the calls it wraps do not score again. */
        private var insideStateSource = false
        /** Inside the condition of a scored branch or loop: a boolean operator there is a path the reader follows. */
        private var conditionDepth = 0

        /** The innermost subtree carrying at least [sharePercent] of [total] and [HOTSPOT_MIN] points, or null when the points are spread. */
        fun hotspot(total: Int, sharePercent: Int, body: KtSourceElement?): Node? = innermost(body) { node ->
            val points = node.total()
            points >= HOTSPOT_MIN && points * 100 >= total * sharePercent
        }

        /**
         * The innermost subtree that [qualifies], going inward as long as a child still does. A
         * subtree that spans the whole [body] is never the answer, but the search goes on inside it.
         */
        fun innermost(body: KtSourceElement?, qualifies: (Node) -> Boolean): Node? {
            var best: Node? = null
            fun visit(node: Node) {
                for (child in node.children) {
                    if (!qualifies(child)) continue
                    if (!child.spans(body)) best = child
                    visit(child)
                }
            }
            visit(root)
            return best
        }

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        private inline fun inChild(label: String, source: KtSourceElement?, block: Block, body: () -> Unit) {
            val child = Node(label, source, block)
            current.children += child
            val saved = current
            current = child
            body()
            current = saved
        }

        override fun visitWhenExpression(whenExpression: FirWhenExpression) {
            whenExpression.subjectVariable?.accept(this)
            // `when { hovered && selected -> Color.A; hovered -> Color.B; else -> Color.C }`: a lookup of
            // constants is one thing to read, however many rows it has.
            val lookup = whenExpression.branches.size > 1 && whenExpression.branches.all { it.result.resultExpression().isConstantLike() }
            if (lookup) current.add(Kind.BRANCHES, 1)
            for (branch in whenExpression.branches) {
                val conditional = branch.condition !is FirElseIfTrueCondition
                if (conditional) {
                    if (!lookup) current.add(Kind.BRANCHES, 1)
                    inCondition(scored = !lookup) { branch.condition.accept(this) }
                }
                inChild(if (conditional) "branch" else "else branch", branch.result.source, Block.BRANCH) { branch.result.accept(this) }
            }
        }

        /** The expression a branch evaluates to: the last statement of its block, or the expression itself. */
        private fun FirExpression.resultExpression(): FirExpression = when (this) {
            is FirBlock -> statements.lastOrNull() as? FirExpression ?: this
            else -> this
        }

        private inline fun inCondition(scored: Boolean, body: () -> Unit) {
            if (scored) conditionDepth++
            body()
            if (scored) conditionDepth--
        }

        override fun visitElvisExpression(elvisExpression: FirElvisExpression) {
            // `?: Color.Unspecified`, `?: ""`, `?: colors.fallback`: a constant or a reference is a default,
            // not a path the reader follows.
            if (!elvisExpression.rhs.isConstantLike()) current.add(Kind.BRANCHES, 1)
            visitElement(elvisExpression)
        }

        /**
         * A literal, or a chain of property reads with no call anywhere in it (`Color.Transparent`,
         * `colors.hover`, `theme.colors.idle`): a value looked up, not computed. A call never is.
         */
        private fun FirExpression.isConstantLike(): Boolean = when (this) {
            is FirLiteralExpression -> true
            is FirSmartCastExpression -> originalExpression.isConstantLike()
            is FirTypeOperatorCall -> argumentList.arguments.singleOrNull()?.isConstantLike() == true
            is FirPropertyAccessExpression -> {
                val receiver = explicitReceiver
                receiver == null || receiver is FirResolvedQualifier || receiver is FirThisReceiverExpression || receiver.isConstantLike()
            }
            else -> false
        }

        override fun visitBooleanOperatorExpression(booleanOperatorExpression: FirBooleanOperatorExpression) {
            // `enabled = a && b` is a value; `if (a && b)` is a path.
            if (conditionDepth > 0) current.add(Kind.BRANCHES, 1)
            visitElement(booleanOperatorExpression)
        }

        override fun visitWhileLoop(whileLoop: FirWhileLoop) {
            val counted = whileLoop.source?.kind !is KtFakeSourceElementKind || whileLoop.source?.kind == KtFakeSourceElementKind.DesugaredForLoop
            if (counted) current.add(Kind.BRANCHES, 1)
            inCondition(scored = counted) { whileLoop.condition.accept(this) }
            whileLoop.block.accept(this)
        }

        override fun visitDoWhileLoop(doWhileLoop: FirDoWhileLoop) {
            current.add(Kind.BRANCHES, 1)
            doWhileLoop.block.accept(this)
            inCondition(scored = true) { doWhileLoop.condition.accept(this) }
        }

        override fun visitProperty(property: FirProperty) {
            // `val x by remember { mutableStateOf() }`: the delegate holds the source; a local val is free.
            visitElement(property)
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            val symbol = propertyAccessExpression.calleeReference.toResolvedCallableSymbol()
            if (symbol?.name?.asString() == "current" && symbol.callableId?.className?.asString()?.contains("CompositionLocal") == true) {
                current.add(Kind.COUPLING, 1)
            }
            visitElement(propertyAccessExpression)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            val callee = functionCall.calleeReference.toResolvedNamedFunctionSymbol()
            val name = callee?.name?.asString()
            if (name in EFFECTS) {
                scoreEffect(functionCall)
                return
            }
            val producesState = !insideStateSource && name !in NOT_STATE_SOURCES &&
                (functionCall.resolvedType.isState(session) || callee?.callableId?.asSingleFqName()?.asString() in stateFactories)
            when {
                producesState -> current.add(Kind.STATES, 1)
                name in LAUNCHERS -> current.add(Kind.EFFECTS, 1)
                name in LOOP_CALLS -> current.add(Kind.BRANCHES, 1)
            }
            val savedInside = insideStateSource
            if (producesState) insideStateSource = true
            functionCall.explicitReceiver?.accept(this)
            val composable = callee != null && callee.isComposable(session)
            val mapping = functionCall.resolvedArgumentMapping
            for (argument in functionCall.argumentList.arguments) {
                val lambda = argument.unwrapArgument() as? FirAnonymousFunctionExpression
                val parameter = if (lambda != null) mapping?.get(argument) ?: mapping?.get(lambda) else null
                if (lambda != null && callee != null && parameter != null && isHandler(callee, parameter)) {
                    // `onClick = { }`: points inside score as usual, the node lets the handler be named.
                    inChild("${parameter.name.asString()} { }", lambda.source, Block.HANDLER) { lambda.anonymousFunction.body?.accept(this) }
                } else if (lambda != null && (composable || name in LOOP_CALLS)) {
                    val block = if (name == "remember" || name == "rememberSaveable") Block.REMEMBER else Block.COMPOSABLE_LAMBDA
                    inChild("${name ?: "lambda"} { }", functionCall.source, block) { lambda.anonymousFunction.body?.accept(this) }
                } else {
                    argument.accept(this)
                }
            }
            insideStateSource = savedInside
        }

        /** Whether a lambda passed for [parameter] of [callee] is an event handler (see the class KDoc). */
        private fun isHandler(callee: FirNamedFunctionSymbol, parameter: FirValueParameter): Boolean {
            val name = callee.name.asString()
            if (NOT_HANDLER_CALLEES.any { if (it.endsWith("*")) name.startsWith(it.dropLast(1)) else name == it }) return false
            val type = parameter.returnTypeRef.coneType
            if (!type.isSomeFunctionType(session)) return false
            // A lambda that produces a value is a builder, one that returns Boolean is a dispatch.
            val functionType = type.fullyExpandedType(session).lowerBoundIfFlexible() as? ConeClassLikeType ?: return false
            val returned = functionType.returnType(session).fullyExpandedType(session).lowerBoundIfFlexible()
            if (!returned.isUnit && !returned.isNothing) return false
            if (type.customAnnotations.any { it.toAnnotationClassId(session) == ComposeNames.COMPOSABLE }) return false
            if (type.isSuspendOrKSuspendFunctionType(session)) return false
            // A receiver lambda is a builder (LazyColumn { items(...) }), not a callback.
            if (type.receiverType(session) != null) return false
            // An inline function runs the lambda in place: it is part of the body, not an event.
            if (callee.isInline && !parameter.isNoinline && !parameter.isCrossinline) return false
            return true
        }

        /** 1 for the effect, 1 more when its body assigns a State; the body's own branches score as usual. */
        private fun scoreEffect(call: FirFunctionCall) {
            val lambda = call.argumentList.arguments.map { it.unwrapArgument() }.lastOrNull() as? FirAnonymousFunctionExpression
            inChild("${call.calleeReference.toResolvedNamedFunctionSymbol()?.name} { }", call.source, Block.EFFECT) {
                current.add(Kind.EFFECTS, 1)
                val body = lambda?.anonymousFunction?.body ?: return@inChild
                val probe = EffectProbe(session)
                body.accept(probe)
                if (probe.writesState) current.add(Kind.EFFECTS, 1)
                body.accept(this)
            }
        }

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
            anonymousFunction.body?.accept(this)
        }
    }

    /** Whether an effect body assigns a `State` (`value = ...`, or a delegated var). */
    private class EffectProbe(private val session: FirSession) : FirVisitorVoid() {
        var writesState = false

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitVariableAssignment(variableAssignment: FirVariableAssignment) {
            // `count++` assigns through a reference to the desugared read of `count`.
            val lValue = variableAssignment.lValue
            val target = (if (lValue is FirDesugaredAssignmentValueReferenceExpression) lValue.expressionRef.value else lValue) as? FirPropertyAccessExpression
            val symbol = target?.calleeReference?.toResolvedCallableSymbol() as? FirPropertySymbol
            if (symbol != null) {
                val receiverIsState = target.explicitReceiver?.resolvedType?.isState(session) == true
                if (symbol.hasDelegate || receiverIsState || symbol.resolvedReturnType.isState(session)) writesState = true
            }
            visitElement(variableAssignment)
        }
    }

    private fun ConeKotlinType.isState(session: FirSession): Boolean {
        val expanded = fullyExpandedType(session)
        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(STATE) ?: return false
        val superType = STATE.constructClassLikeType(Array(symbol.typeParameterSymbols.size) { ConeStarProjection }, isMarkedNullable = true)
        return !expanded.isNullableNothing && AbstractTypeChecker.isSubtypeOf(session.typeContext, expanded, superType)
    }
}

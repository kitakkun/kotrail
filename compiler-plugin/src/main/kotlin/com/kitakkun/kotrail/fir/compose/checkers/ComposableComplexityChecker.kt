@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.KotrailDiagnostics
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
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isNullableNothing
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.resolvedType
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
 * | A state source: a call that produces a `State` (`remember { mutableStateOf(...) }`, `rememberSaveable`, `derivedStateOf`, `collectAsState`), or a call listed in `stateFactories` | 1 each |
 * | An effect (`LaunchedEffect`, `DisposableEffect`, `produceState`, `SideEffect`) | 1, +1 when it writes a `State`; its body's branches count as branches |
 * | A `launch` / `async` from a handler | 1 each |
 * | A branch (`if`, a `when` case, `?:` with a computed fallback), a boolean `&&` / `||`, a loop | 1 each |
 * | A `CompositionLocal.current` read | 1 each |
 * | A callback parameter beyond four | 1 each, at most 4 |
 *
 * Neither line count nor call nesting counts: [FunctionLengthChecker] and [ComposableNestingChecker]
 * own those. Every lambda handed to a composable (`Column { }`, `items { }`), every effect and every
 * branch is a subtree with a score of its own, so that a composable over the limit is told which
 * block to extract (the innermost subtree carrying at least `hotspotShare` percent of the total)
 * and what kind of extraction fits it, or, when the points are spread, which kinds dominate. Every
 * composable's score and breakdown is also written to a record for the `kotrailComplexity`
 * report, under or over the limit.
 */
object ComposableComplexityChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    private val EFFECTS = setOf("LaunchedEffect", "DisposableEffect", "produceState", "SideEffect")
    private val LAUNCHERS = setOf("launch", "async")
    private val LOOP_CALLS = setOf("forEach", "forEachIndexed", "map", "items", "itemsIndexed", "repeat", "fastForEach")
    private val STATE = ClassId(FqName("androidx.compose.runtime"), Name.identifier("State"))
    private const val FREE_CALLBACKS = 4
    private const val MAX_CALLBACK_POINTS = 4
    private const val HOTSPOT_MIN = 5
    /** A kind that carries at least this share of the total is named as dominating a spread score. */
    private const val DOMINANT_SHARE = 30

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

        config.complexityDir?.let { directory ->
            val path = context.containingFileSymbol?.sourceFile?.path
            if (path != null) ComplexityRecords.record(directory, path, declaration, total, root, scorer.hotspot(total, settings.hotspotShare))
        }
        if (settings.maxScore <= 0 || total <= settings.maxScore) return

        val hotspot = scorer.hotspot(total, settings.hotspotShare)
        val advice = if (hotspot != null) {
            "'${hotspot.label}' carries ${hotspot.total()} of them: ${hotspot.extraction()}"
        } else {
            val byKind = root.byKind().filterValues { it > 0 }.entries.sortedByDescending { it.value }
            val dominant = byKind.filter { it.value * 100 >= total * DOMINANT_SHARE }.ifEmpty { byKind.take(1) }
            val named = dominant.joinToString(" and ") { (kind, points) -> "${kind.label} ($points)" }
            "the points are spread across the body with no single block to extract; $named ${if (dominant.size == 1) "dominates" else "dominate"}: ${dominant.first().key.advice}"
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
    enum class Block { COMPOSABLE_LAMBDA, REMEMBER, EFFECT, BRANCH }

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

        /** How to take this block out of the composable. */
        fun extraction(): String = when (block) {
            Block.COMPOSABLE_LAMBDA -> "extract it into a composable of its own"
            Block.REMEMBER -> "move the computation into a plain function"
            Block.EFFECT -> "move its body into a non-composable handler or an effect helper of its own"
            Block.BRANCH -> "give this variant of the UI a composable of its own"
        }
    }

    /** Walks a body, scoring into the current [Node]; a lambda handed to a composable, an effect and a branch open a child. */
    private class Scorer(private val session: FirSession, private val stateFactories: Set<String>) : FirVisitorVoid() {
        val root = Node("body", null, Block.COMPOSABLE_LAMBDA)
        private var current = root
        /** Inside a call that already scored as a state source: the calls it wraps do not score again. */
        private var insideStateSource = false

        /** The innermost subtree carrying at least [sharePercent] of [total] and [HOTSPOT_MIN] points, or null when the points are spread. */
        fun hotspot(total: Int, sharePercent: Int): Node? {
            var best: Node? = null
            fun visit(node: Node) {
                for (child in node.children) {
                    val points = child.total()
                    if (points >= HOTSPOT_MIN && points * 100 >= total * sharePercent) {
                        best = child
                        visit(child)
                    }
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
            for (branch in whenExpression.branches) {
                val conditional = branch.condition !is FirElseIfTrueCondition
                if (conditional) {
                    current.add(Kind.BRANCHES, 1)
                    branch.condition.accept(this)
                }
                inChild(if (conditional) "branch" else "else branch", branch.result.source, Block.BRANCH) { branch.result.accept(this) }
            }
        }

        override fun visitElvisExpression(elvisExpression: FirElvisExpression) {
            // `?: Color.Unspecified`, `?: ""`: a constant fallback is a default, not a path the reader follows.
            if (!elvisExpression.rhs.isConstantLike()) current.add(Kind.BRANCHES, 1)
            visitElement(elvisExpression)
        }

        private fun FirExpression.isConstantLike(): Boolean = when (this) {
            is FirLiteralExpression -> true
            is FirPropertyAccessExpression -> {
                val receiver = explicitReceiver
                receiver == null || receiver is FirResolvedQualifier || receiver is FirThisReceiverExpression
            }
            else -> false
        }

        override fun visitBooleanOperatorExpression(booleanOperatorExpression: FirBooleanOperatorExpression) {
            current.add(Kind.BRANCHES, 1)
            visitElement(booleanOperatorExpression)
        }

        override fun visitWhileLoop(whileLoop: FirWhileLoop) {
            if (whileLoop.source?.kind !is KtFakeSourceElementKind || whileLoop.source?.kind == KtFakeSourceElementKind.DesugaredForLoop) current.add(Kind.BRANCHES, 1)
            visitElement(whileLoop)
        }

        override fun visitDoWhileLoop(doWhileLoop: FirDoWhileLoop) {
            current.add(Kind.BRANCHES, 1)
            visitElement(doWhileLoop)
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
            val producesState = !insideStateSource &&
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
            for (argument in functionCall.argumentList.arguments) {
                val lambda = argument.unwrapArgument() as? FirAnonymousFunctionExpression
                if (lambda != null && (composable || name in LOOP_CALLS)) {
                    val block = if (name == "remember" || name == "rememberSaveable") Block.REMEMBER else Block.COMPOSABLE_LAMBDA
                    inChild("${name ?: "lambda"} { }", functionCall.source, block) { lambda.anonymousFunction.body?.accept(this) }
                } else {
                    argument.accept(this)
                }
            }
            insideStateSource = savedInside
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
            val target = variableAssignment.lValue as? FirPropertyAccessExpression
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

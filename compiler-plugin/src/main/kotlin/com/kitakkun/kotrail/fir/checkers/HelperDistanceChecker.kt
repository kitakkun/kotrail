@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailNarrativeOrder
import com.kitakkun.kotrail.fir.FixEdit
import com.kitakkun.kotrail.fir.HelperDistance
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.TunableDiagnostic3
import com.kitakkun.kotrail.fir.fix.FixBuilder
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.reportingAt
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.isConst
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.text

/**
 * The distance check of narrativeOrder: reports a private helper that sits far from its only
 * caller, with mostly unrelated code between the two (`KOTRAIL_HELPER_FAR_FROM_CALLER`, see
 * [HelperDistance.farFromCaller]). Run by [NarrativeOrderChecker] over its walk of the file.
 *
 * The helper is reported only when its caller reads as code a reader follows: a function, or a
 * property with an accessor body; a class header, an `init` block or a property initializer is
 * left alone. When the two are siblings (both top-level, or both members of one class body), the
 * fix moves a helper below its caller right after it, the way the order check's fix does, and a
 * private top-level property above its caller right before it; a helper property whose
 * initializer reads another property of the file keeps its place, since moving it would change
 * the order in which the two are initialized. A far run of helpers with the same caller, written
 * one after another, is reported once, on its first member, and its fix moves the whole run as
 * a block. Either fix takes along the private functions that only the moved helpers call, when
 * the move would leave them far from their caller or above it ([HelperDistance.movedTogether]).
 *
 * A top-level helper function whose only caller is a member of a class is reported instead as
 * `KOTRAIL_HELPER_OUTSIDE_CALLER_CLASS`, without a fix: the order check keeps it after the
 * class, so a long class makes it far whatever its place, and it belongs inside the class, a
 * private member right after its caller. Moving it there is left to the author, since in the
 * class body the class's members can capture the names its body resolves today. A top-level
 * extension function with such a caller is not reported at all: it means something for its
 * receiver alone and belongs at the top level, where the class's length keeps it far.
 *
 * The declarations measured are functions and properties, at the top level and in classes and
 * objects at any nesting, and every class of the file, whose "body" is its header (through the
 * primary constructor) and its `init` blocks: a constructor call leads there. Every one also
 * carries the class that declares it, whether it is a companion object or any `object`, and
 * whether it is private. A property also carries whether it is a constant (a `const val`, or a
 * `val` initialized with a literal, arithmetic over literals and `const` properties, or a single
 * call on such a receiver, `16.dp`), and a function the lines of the same-file class it
 * returns, if any: both exempt the helper.
 */
internal object HelperDistanceChecker {
    /** The model of [file] with the sites of its members, or `null` when the file's text is not at hand. */
    fun collect(file: FirFile, uses: DeclarationUses): Collector? = file.source?.text?.toString()?.let { Collector(uses, it) }

    /**
     * The sources a fix moves when it moves the declarations [keys] next to [anchor], right after
     * it or, with [before], right before it: theirs, then those of the functions that move along
     * ([HelperDistance.movedTogether]). `null` when one of [keys] is not in [collected], as a
     * member of a local class is not.
     */
    fun movedSources(
        collected: Collector,
        keys: List<Any>,
        anchor: KtSourceElement,
        before: Boolean,
        maxCallerDistance: Int,
        maxHelperNoise: Int,
    ): List<KtSourceElement>? {
        val moved = keys.map { collected.byKey[it] ?: return null }
        val group = HelperDistance.movedTogether(moved, collected.linesOf(anchor), before, collected.byKey, maxCallerDistance, maxHelperNoise)
        return group.map { collected.sites[it.key]?.declaration?.source ?: return null }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    fun check(file: FirFile, collected: Collector, settings: KotrailNarrativeOrder) {
        val members = collected.byKey
        val found = collected.sites.mapNotNull { (key, helper) ->
            if (helper.declaration.isOverride) return@mapNotNull null
            val member = members[key] ?: return@mapNotNull null
            val far = HelperDistance.farFromCaller(member, members, settings.maxCallerDistance, settings.maxHelperNoise) ?: return@mapNotNull null
            val caller = collected.sites[far.caller.key]?.takeIf { it.readsAsCode } ?: return@mapNotNull null
            if (helper.isTopLevelExtension() && caller.enclosing.isNotEmpty()) return@mapNotNull null
            FarSite(helper, member, far, caller)
        }
        for (run in runsOf(found, members)) reportRun(file, run, collected, settings)
    }

    /**
     * Reports [run], far helpers of one caller written one after another, once, on its first
     * member: `'homeIcon'`, or `the run of 'homeIcon' and the 3 helpers after it`, at the
     * distance of the member nearest the caller. The fix moves the whole run, in its order, and
     * the functions that move along with it.
     */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportRun(file: FirFile, run: List<FarSite>, collected: Collector, settings: KotrailNarrativeOrder) {
        val members = collected.byKey
        val first = run.first()
        val helper = first.site
        val caller = first.caller
        val helperSources = run.map { it.site.declaration.source ?: return }
        val callerSource = caller.declaration.source ?: return
        val name = "'${helper.declaration.symbol.name.asString()}'"
        val helpers = when (val others = run.size - 1) {
            0 -> name
            1 -> "the run of $name and the helper after it"
            else -> "the run of $name and the $others helpers after it"
        }
        val site = helper.enclosing + helper.declaration
        val callerName = caller.declaration.symbol.name.asString()
        val callerClass = caller.enclosing.lastOrNull()
        if (callerClass != null && run.all { it.site.enclosing.isEmpty() && it.member.kind == HelperDistance.Kind.FUNCTION }) {
            val className = callerClass.symbol.classId.relativeClassName.asString()
            report(site, helperSources.first(), KotrailDiagnostics.HELPER_OUTSIDE_CALLER_CLASS, helpers, callerName, className, fix = emptyList())
            return
        }
        val container = helper.enclosing.lastOrNull()?.source ?: file.source
        fun moved(): List<KtSourceElement> =
            movedSources(collected, run.map { it.member.key }, callerSource, first.far.above, settings.maxCallerDistance, settings.maxHelperNoise)
                ?: helperSources
        val fix = when {
            run.any { !it.site.isSiblingOf(caller) || !it.member.canMove(members) } -> emptyList()
            first.far.above -> FixBuilder.over(container)?.moveDeclarationsBefore(moved(), callerSource) ?: emptyList()
            else -> FixBuilder.over(container)?.moveDeclarationsAfter(moved(), callerSource) ?: emptyList()
        }
        val distance = run.minOf { it.far.distance }
        report(site, helperSources.first(), KotrailDiagnostics.HELPER_FAR_FROM_CALLER, helpers, callerName, distance.toString(), fix)
    }

    /** Reports [diagnostic] on [source] at [site], the helper last: what it names, its caller, and the distance or the caller's class. */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun report(
        site: List<FirDeclaration>,
        source: KtSourceElement,
        diagnostic: TunableDiagnostic3<String, String, String>,
        helpers: String,
        callerName: String,
        detail: String,
        fix: List<FixEdit>,
    ) {
        if (isSuppressedOn(site, diagnostic.baseName, diagnostic.rule)) return
        reportingAt(site) { reportKotrail(source, diagnostic, helpers, callerName, detail, fix) }
    }

    /** A helper at [site], far from its only caller at [caller], with its member of the file's model. */
    private class FarSite(val site: Site, val member: HelperDistance.Member<Any>, val far: HelperDistance.FarHelper<Any>, val caller: Site)

    /**
     * [found] grouped in runs, each in source order: a helper joins the run of the declaration
     * right before it in the same container when that one is a far helper of the same caller.
     */
    private fun runsOf(found: List<FarSite>, members: Map<Any, HelperDistance.Member<Any>>): List<List<FarSite>> {
        val byKey = found.associateBy { it.member.key }
        val runOf = HashMap<Any, MutableList<FarSite>>()
        val runs = mutableListOf<MutableList<FarSite>>()
        for (helper in found.sortedBy { it.member.start }) {
            val previous = members.values
                .filter { it.owner == helper.member.owner && it.start < helper.member.start }
                .maxByOrNull { it.start }
                ?.let { byKey[it.key] }
                ?.takeIf { it.far.caller.key == helper.far.caller.key }
            val run = previous?.let { runOf.getValue(it.member.key) } ?: mutableListOf<FarSite>().also(runs::add)
            run += helper
            runOf[helper.member.key] = run
        }
        return runs
    }

    /** Whether this is a top-level extension function, which means something for its receiver alone. */
    private fun Site.isTopLevelExtension(): Boolean = enclosing.isEmpty() && declaration is FirNamedFunction && declaration.receiverParameter != null

    /** Whether both are top-level, or both members of the same class body: the only case the move keeps the meaning of. */
    private fun Site.isSiblingOf(other: Site): Boolean = enclosing.lastOrNull() === other.enclosing.lastOrNull()

    /** A function moves freely; a property only when its initializer reads no other property of the file, whose initialization order the move would change. */
    private fun HelperDistance.Member<Any>.canMove(members: Map<Any, HelperDistance.Member<Any>>): Boolean =
        kind != HelperDistance.Kind.PROPERTY || references.none { members[it]?.kind == HelperDistance.Kind.PROPERTY }

    /**
     * A function or property of the file as a diagnostic needs it: the declaration, the classes
     * that enclose it (outermost first), and whether its body is code a reader follows into a
     * call, a function body or a property accessor, rather than an initializer.
     */
    class Site(val declaration: FirCallableDeclaration, val enclosing: List<FirRegularClass>, val readsAsCode: Boolean)

    /** The members of the file's model, built from the containers that are not local, and their sites. */
    class Collector(private val uses: DeclarationUses, text: String) {
        private val lines = LineIndex(text)
        private val members = mutableListOf<HelperDistance.Member<Any>>()
        val sites = LinkedHashMap<Any, Site>()

        private val containers = uses.containers.filter { !it.isLocal }

        /** The lines every class of the file spans, header to closing brace, by class. */
        private val classLines: Map<ClassId, IntRange> = containers
            .mapNotNull { container -> container.owner?.let { klass -> klass.source?.takeIf { it.isReal() }?.let { klass.symbol.classId to lines.of(it) } } }
            .toMap()

        init {
            for (container in containers) {
                val enclosing = container.enclosing.filterIsInstance<FirRegularClass>()
                container.owner?.let(::addClass)
                for (declaration in container.declarations) {
                    when (declaration) {
                        is FirNamedFunction -> addFunction(declaration, enclosing)
                        is FirProperty -> addProperty(declaration, enclosing)
                        else -> Unit
                    }
                }
            }
        }

        val byKey: Map<Any, HelperDistance.Member<Any>> = members.associateBy { it.key }

        /** The lines [source] spans in the file, 1-based. */
        fun linesOf(source: KtSourceElement): IntRange = lines.of(source)

        private fun addFunction(function: FirNamedFunction, enclosing: List<FirRegularClass>) {
            val source = function.source?.takeIf { it.isReal() } ?: return
            members += HelperDistance.Member(
                function.symbol, listOf(lines.of(source)), uses.of(function),
                HelperDistance.Kind.FUNCTION, enclosing.lastOrNull()?.symbol?.classId, companionOf = null, isObject = false,
                isPrivate = function.visibility == Visibilities.Private, isConstant = false,
                builds = function.returnTypeRef.coneTypeOrNull?.classId?.let(classLines::get),
            )
            sites[function.symbol] = Site(function, enclosing, readsAsCode = function.body != null)
        }

        private fun addProperty(property: FirProperty, enclosing: List<FirRegularClass>) {
            val source = property.source ?: return
            val fromParameter = source.kind == KtFakeSourceElementKind.PropertyFromParameter
            if (source.kind is KtFakeSourceElementKind && !fromParameter) return
            members += HelperDistance.Member(
                property.symbol, listOf(lines.of(source)), if (fromParameter) emptySet() else uses.of(property),
                HelperDistance.Kind.PROPERTY, enclosing.lastOrNull()?.symbol?.classId, companionOf = null, isObject = false,
                isPrivate = property.visibility == Visibilities.Private, isConstant = !fromParameter && property.isConstant(), builds = null,
            )
            if (!fromParameter) sites[property.symbol] = Site(property, enclosing, readsAsCode = property.hasAccessorBody())
        }

        /** A class, whose lines are its header (through the primary constructor) and its `init` blocks. */
        private fun addClass(klass: FirRegularClass) {
            val source = klass.source?.takeIf { it.isReal() } ?: return
            val primary = klass.declarations.filterIsInstance<FirConstructor>().firstOrNull { it.isPrimary }
            val inits = klass.declarations.filterIsInstance<FirAnonymousInitializer>().filter { it.source.isReal() }
            val classStart = lines.of(source).first
            val headerEnd = primary?.source?.takeIf { it.isReal() }?.let { lines.of(it).last } ?: classStart
            val ranges = listOf(classStart..maxOf(classStart, headerEnd)) + inits.mapNotNull { init -> init.source?.let(lines::of) }
            val owner = klass.symbol.classId.outerClassId
            members += HelperDistance.Member(
                klass.symbol.classId, ranges, uses.headerOf(klass), HelperDistance.Kind.CLASS, owner,
                companionOf = owner.takeIf { klass.isCompanion },
                isObject = klass.classKind == ClassKind.OBJECT,
                isPrivate = klass.visibility == Visibilities.Private,
                isConstant = false, builds = null,
            )
        }

        /** A `const val`, or a `val` without accessors or a delegate whose initializer is a constant expression. */
        private fun FirProperty.isConstant(): Boolean {
            if (isConst) return true
            if (isVar || delegate != null || getter?.source.isReal()) return false
            return initializer?.isConstantExpression() == true
        }

        /**
         * A literal, a string template over constants, a `const` property, or a call or property
         * access on a constant receiver whose arguments are constants: `1 + 2`, `30 * 1000`,
         * `16.dp`, `"a" + "b"`.
         */
        private fun FirExpression.isConstantExpression(): Boolean = when (this) {
            is FirLiteralExpression -> true
            is FirStringConcatenationCall -> argumentList.arguments.all { it.isConstantExpression() }
            is FirFunctionCall -> explicitReceiver?.isConstantExpression() == true && argumentList.arguments.all { it.isConstantExpression() }
            is FirPropertyAccessExpression ->
                (calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol)?.isConst == true ||
                    explicitReceiver?.isConstantExpression() == true
            else -> false
        }

        private fun FirProperty.hasAccessorBody(): Boolean =
            getter?.takeIf { it.source.isReal() }?.body != null || setter?.takeIf { it.source.isReal() }?.body != null
    }

    private fun KtSourceElement?.isReal(): Boolean = this != null && kind !is KtFakeSourceElementKind

    /** 1-based line numbers of offsets in the file's text. */
    private class LineIndex(text: String) {
        private val starts: IntArray = buildList {
            add(0)
            text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
        }.toIntArray()

        fun of(source: KtSourceElement): IntRange = line(source.startOffset)..line(maxOf(source.startOffset, source.endOffset - 1))

        private fun line(offset: Int): Int {
            val found = starts.binarySearch(offset)
            return if (found >= 0) found + 1 else -found - 1
        }
    }
}

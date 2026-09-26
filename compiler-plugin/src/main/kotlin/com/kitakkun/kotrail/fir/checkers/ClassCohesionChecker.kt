@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.test.isTestFunction
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.isData
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId

/**
 * Reports a class whose members fall into groups that share nothing:
 *
 * ```kotlin
 * class ScreenViewModel(private val repo: Repo, private val exporter: Exporter, private val session: Session) {
 *     var draft: Draft? = null
 *     fun onEdit(text: String) { draft = draft?.copy(text = text) }     // group 1: draft, repo
 *     fun onSave() { repo.save(draft ?: return) }
 *     fun onExport() { exporter.export() }                              // group 2: exporter
 *     fun onLogout() { session.clear() }                                // group 3: session
 * }
 * ```
 *
 * Two members are related when one calls the other or both touch the same property of the
 * class (a member property, a constructor property, one inherited from the direct supertype);
 * the groups are the connected components of that relation. A class with several groups is
 * several classes sharing a name, most often a screen's "view model" that took every event
 * handler the screen needed: each group is a class of its own, or the events become an
 * interface with one implementation per group, injected where the screen needs them. Members
 * that touch no property and call no member are helpers, not a group, and are left out; so are
 * interfaces, enums, annotation classes, data classes, companion objects, test classes, and
 * classes with fewer than `minMembers` members that count.
 */
object ClassCohesionChecker : FirRegularClassChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.CLASS_COHESION)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.isLocal || declaration.isData || declaration.isCompanion) return
        if (declaration.classKind != ClassKind.CLASS && declaration.classKind != ClassKind.OBJECT) return
        val session = context.session
        val functions = declaration.declarations.filterIsInstance<FirNamedFunction>().filter { it.body != null && !it.isAbstract }
        if (functions.any { it.symbol.isTestFunction(session, config.test.annotations) }) return

        val own = HashSet<ClassId>().apply {
            add(declaration.symbol.classId)
            declaration.superTypeRefs.mapNotNullTo(this) { it.coneType.classId }
        }
        val groups = UnionFind()
        val touched = HashMap<FirNamedFunction, Set<FirBasedSymbol<*>>>()
        val members = functions.associateBy { it.symbol as FirBasedSymbol<*> }
        for (function in functions) {
            val collector = MemberCollector(own)
            function.body?.accept(collector)
            val related = collector.found.filter { it is FirPropertySymbol || it in members }
            touched[function] = related.toSet()
            for (symbol in related) groups.union(function.symbol, symbol)
        }
        // A member that touches nothing of the class is a helper, not a group of its own.
        val counted = functions.filter { touched[it]!!.isNotEmpty() || functions.any { other -> other !== it && it.symbol in touched[other]!! } }
        if (counted.size < config.classCohesion.minMembers) return
        val components = counted.groupBy { groups.find(it.symbol) }.values
        if (components.size < 2) return

        val description = components.sortedByDescending { it.size }.joinToString("; ") { group ->
            val properties = group.flatMap { touched[it]!! }.filterIsInstance<FirPropertySymbol>().map { it.name.asString() }.distinct().sorted()
            group.joinToString(", ") { it.name.asString() } + if (properties.isEmpty()) "" else " (${properties.joinToString(", ")})"
        }
        reportKotrail(source, KotrailDiagnostics.CLASS_NOT_COHESIVE, components.size.toString(), description)
    }

    /** The member properties and member functions of the class (and its direct supertypes) a body touches. */
    private class MemberCollector(private val own: Set<ClassId>) : FirVisitorVoid() {
        val found = LinkedHashSet<FirBasedSymbol<*>>()

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            note(propertyAccessExpression.calleeReference.toResolvedCallableSymbol())
            visitElement(propertyAccessExpression)
        }

        override fun visitVariableAssignment(variableAssignment: FirVariableAssignment) {
            note((variableAssignment.lValue as? FirPropertyAccessExpression)?.calleeReference?.toResolvedCallableSymbol())
            visitElement(variableAssignment)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            note(functionCall.calleeReference.toResolvedCallableSymbol())
            visitElement(functionCall)
        }

        override fun visitCallableReferenceAccess(callableReferenceAccess: FirCallableReferenceAccess) {
            note(callableReferenceAccess.calleeReference.toResolvedCallableSymbol())
            visitElement(callableReferenceAccess)
        }

        private fun note(symbol: FirBasedSymbol<*>?) {
            val owner = when (symbol) {
                is FirPropertySymbol -> symbol.callableId?.classId
                is FirNamedFunctionSymbol -> symbol.callableId.classId
                else -> null
            } ?: return
            if (owner in own) found += symbol!!
        }
    }

    private class UnionFind {
        private val parent = HashMap<FirBasedSymbol<*>, FirBasedSymbol<*>>()

        fun find(symbol: FirBasedSymbol<*>): FirBasedSymbol<*> {
            var current = symbol
            while (true) {
                val next = parent[current] ?: return current
                if (next == current) return current
                current = next
            }
        }

        fun union(a: FirBasedSymbol<*>, b: FirBasedSymbol<*>) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
    }
}

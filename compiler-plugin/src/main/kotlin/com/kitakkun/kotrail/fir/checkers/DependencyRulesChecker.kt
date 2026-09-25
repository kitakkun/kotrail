package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailDependencyPolicy
import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.exclude.Glob
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * Keeps the project's layers apart: a file in a package a policy names must not refer to the
 * packages the policy denies.
 *
 * ```yaml
 * rules:
 *   dependencyRules:
 *     policies:
 *       ui: { from: com.acme.ui.*, deny: [com.acme.data.*], allow: [com.acme.data.model.*] }
 * ```
 *
 * ```kotlin
 * package com.acme.ui.settings
 * import com.acme.data.db.UserDao          // reported: ui does not see data
 * import com.acme.data.model.User          // fine: allowed
 * ```
 *
 * Imports and every resolved reference in the file's body (types, calls, property reads,
 * qualifiers) are checked; a package never denies itself or its subpackages. Each offending
 * package is reported once per file and policy, at its first reference, so that a file that
 * leans on a forbidden layer produces one finding per layer rather than one per line. Only
 * packages a `deny` glob matches are reported: the standard library is reported only when a
 * policy names it. This is the compile-time counterpart of ArchUnit or Konsist for the crossing
 * an AI assistant makes most readily: reaching for whatever makes the code work.
 */
object DependencyRulesChecker : FirFileChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.DEPENDENCY_RULES)) return
        val filePackage = declaration.packageDirective.packageFqName.asString()
        val policies = config.dependencyPolicies.filter { it.from.covers(filePackage) }
        if (policies.isEmpty()) return
        val session = context.session

        val references = mutableListOf<Pair<String, KtSourceElement>>()
        for (import in declaration.imports) {
            val fqName = import.importedFqName ?: continue
            val source = import.source ?: continue
            references += importedPackage(fqName, import.isAllUnder, session) to source
        }
        declaration.accept(ReferenceCollector(references))

        val reported = mutableSetOf<Pair<String, String>>()
        for ((packageName, source) in references) {
            if (source.kind is KtFakeSourceElementKind) continue
            if (packageName == filePackage || packageName.startsWith("$filePackage.")) continue
            for (policy in policies) {
                if (!policy.denies(packageName)) continue
                if (!reported.add(packageName to policy.name)) continue
                reportKotrail(source, KotrailDiagnostics.DEPENDENCY_NOT_ALLOWED, packageName, "policy '${policy.name}'")
            }
        }
    }

    private fun KotrailDependencyPolicy.denies(packageName: String): Boolean =
        deny.any { it.covers(packageName) } && allow.none { it.covers(packageName) }

    /** A package glob covers a package when it matches it, or matches it with a trailing dot: `com.acme.ui.*` covers `com.acme.ui` itself and its subpackages. */
    private fun Glob.covers(packageName: String): Boolean = matches(packageName) || matches("$packageName.")

    /** The package an import brings in: the name itself for `x.y.*`, the class's package for a class, the parent otherwise. */
    private fun importedPackage(fqName: FqName, isAllUnder: Boolean, session: FirSession): String {
        if (isAllUnder) return fqName.asString()
        val classId = ClassId.topLevel(fqName)
        if (session.symbolProvider.getClassLikeSymbolByClassId(classId) != null) return fqName.parent().asString()
        // A nested class import: walk up until a class resolves, then take that class's package.
        var candidate = fqName.parent()
        while (!candidate.isRoot) {
            val symbol = session.symbolProvider.getClassLikeSymbolByClassId(ClassId.topLevel(candidate))
            if (symbol != null) return symbol.classId.packageFqName.asString()
            candidate = candidate.parent()
        }
        return fqName.parent().asString()
    }

    /** Every package the file's body refers to, with the source of the reference. */
    private class ReferenceCollector(private val into: MutableList<Pair<String, KtSourceElement>>) : FirVisitorVoid() {
        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitResolvedTypeRef(resolvedTypeRef: FirResolvedTypeRef) {
            val source = resolvedTypeRef.source
            val packageName = resolvedTypeRef.coneType.classId?.packageFqName?.asString()
            if (source != null && packageName != null) into += packageName to source
            resolvedTypeRef.acceptChildren(this)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            record(functionCall.calleeReference.toResolvedCallableSymbol()?.callableId?.packageName?.asString(), functionCall.source)
            functionCall.acceptChildren(this)
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            record(propertyAccessExpression.calleeReference.toResolvedCallableSymbol()?.callableId?.packageName?.asString(), propertyAccessExpression.source)
            propertyAccessExpression.acceptChildren(this)
        }

        override fun visitResolvedQualifier(resolvedQualifier: FirResolvedQualifier) {
            record(resolvedQualifier.packageFqName.asString(), resolvedQualifier.source)
            resolvedQualifier.acceptChildren(this)
        }

        private fun record(packageName: String?, source: KtSourceElement?) {
            if (packageName != null && source != null) into += packageName to source
        }
    }
}

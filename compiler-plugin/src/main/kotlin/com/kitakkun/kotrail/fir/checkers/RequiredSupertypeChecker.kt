package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.reportSite
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.resolve.isSubclassOf
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Enforces the project's required-supertype policies: a class matching the predicate of a
 * `requiredSupertype.policies.<name>` entry must extend or implement the type the entry names.
 *
 * ```yaml
 * rules:
 *   requiredSupertype:
 *     policies:
 *       viewModels: class && name(*ViewModel) -> com.acme.arch.BaseViewModel
 *       repositories: class && package(com.acme.data.*) && name(*Repository) -> com.acme.data.Repository
 * ```
 *
 * ```kotlin
 * class SettingsViewModel : ViewModel()            // reported: needs BaseViewModel
 * class MainViewModel : BaseViewModel()            // fine
 * ```
 *
 * The predicates are the same language as `exclude`. The supertype may be reached through
 * intermediate classes and interfaces. A supertype that does not resolve reports nothing, so
 * that a misspelled name does not fail every class the policy covers; the supertype itself,
 * annotation classes, and local classes are left alone.
 */
object RequiredSupertypeChecker : FirRegularClassChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.REQUIRED_SUPERTYPE)) return
        val policies = config.requiredSupertypes
        if (policies.isEmpty()) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.isLocal || declaration.classKind == ClassKind.ANNOTATION_CLASS) return
        val session = context.session
        val symbol = declaration.symbol

        val site by lazy { reportSite() }
        for (policy in policies) {
            if (!policy.predicate.matches(site)) continue
            val required = resolveClass(policy.supertype, session) ?: continue
            if (required == symbol) continue
            if (symbol.isSubclassOf(required.toLookupTag(), session, isStrict = true, lookupInterfaces = true)) continue
            reportKotrail(
                source,
                KotrailDiagnostics.SUPERTYPE_REQUIRED,
                declaration.name.asString(),
                "${policy.supertype} (policy '${policy.name}')",
            )
        }
    }

    /** The class or interface a fully qualified name denotes, trying nested-class splits from the right; `null` if none resolves. */
    private fun resolveClass(fqn: String, session: FirSession): FirClassSymbol<*>? {
        val segments = fqn.split('.')
        if (segments.any { it.isEmpty() }) return null
        for (split in segments.size - 1 downTo 0) {
            val packageName = if (split == 0) FqName.ROOT else FqName.fromSegments(segments.subList(0, split))
            var classId = ClassId(packageName, Name.identifier(segments[split]))
            for (nested in segments.subList(split + 1, segments.size)) classId = classId.createNestedClassId(Name.identifier(nested))
            val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirClassSymbol<*>
            if (symbol != null) return symbol
        }
        return null
    }
}

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.reportSite
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.EffectiveVisibility
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirBasicDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.effectiveVisibility
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.declarations.utils.isOverride

/**
 * Enforces the project's visibility policy: a declaration matching `visibilityPolicy.private`
 * must be private, one matching `visibilityPolicy.internal` must be internal or private.
 *
 * ```properties
 * visibilityPolicy.private=composable && name(*Preview)
 * visibilityPolicy.internal=name(*Impl) || package(com.acme.*.internal.*)
 * ```
 *
 * The predicates are the same language as `exclude`, so a policy is written in the terms a
 * reader already knows: names, packages, annotations, receivers, modifiers. Effective visibility
 * is what counts, so a public member of an internal class already satisfies an `internal`
 * policy. Overrides are left alone, since their visibility is fixed by what they override, and
 * so are local declarations.
 */
object VisibilityPolicyChecker : FirBasicDeclarationChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirDeclaration) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.VISIBILITY_POLICY)) return
        val policy = config.visibilityPolicy
        if (policy.isEmpty) return
        if (declaration !is FirNamedFunction && declaration !is FirProperty && declaration !is FirRegularClass) return
        val member = declaration as FirMemberDeclaration
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.isLocal) return
        if (declaration is FirNamedFunction && declaration.isOverride) return
        if (declaration is FirProperty && declaration.isOverride) return

        val effective = member.effectiveVisibility
        val isPrivate = effective == EffectiveVisibility.PrivateInClass ||
            effective == EffectiveVisibility.PrivateInFile ||
            effective == EffectiveVisibility.Local
        val name = memberName(declaration)
        val site by lazy { reportSite() }

        val required = when {
            policy.private != null && !isPrivate && policy.private.matches(site) -> "private"
            policy.internal != null && effective.publicApi && policy.internal.matches(site) -> "internal"
            else -> return
        }
        reportKotrail(source, KotrailDiagnostics.VISIBILITY_TOO_WIDE, name, required)
    }

    private fun memberName(declaration: FirDeclaration): String = when (declaration) {
        is FirNamedFunction -> declaration.name.asString()
        is FirProperty -> declaration.name.asString()
        is FirRegularClass -> declaration.name.asString()
        else -> "?"
    }
}

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.reportSite
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirBasicDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.isLocal

/**
 * Enforces the project's required-annotation policies: a declaration matching the predicate of a
 * `requiredAnnotation.<name>` entry must carry the annotation the entry names.
 *
 * ```properties
 * requiredAnnotation.screens=composable && name(*Screen) -> com.acme.navigation.Screen
 * requiredAnnotation.entities=class(*Entity) && package(com.acme.db.*) -> androidx.room.Entity
 * ```
 *
 * The predicates are the same language as `exclude`. Only the declaration's own annotations
 * satisfy a policy; an annotation on the enclosing class does not, since that is a different
 * declaration being annotated. Local declarations are left alone.
 */
object RequiredAnnotationChecker : FirBasicDeclarationChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirDeclaration) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.REQUIRED_ANNOTATION)) return
        val policies = config.requiredAnnotations
        if (policies.isEmpty()) return
        if (declaration !is FirNamedFunction && declaration !is FirProperty && declaration !is FirRegularClass) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.isLocal) return

        val site by lazy { reportSite() }
        val present by lazy {
            declaration.annotations.mapNotNullTo(HashSet()) {
                it.toAnnotationClassId(context.session)?.asSingleFqName()?.asString()
            }
        }
        for (policy in policies) {
            if (policy.annotation in present) continue
            if (!policy.predicate.matches(site)) continue
            reportKotrail(
                source,
                KotrailDiagnostics.REQUIRED_ANNOTATION_MISSING,
                site.declarationName ?: "?",
                "@${policy.annotation} (policy '${policy.name}')",
            )
        }
    }
}

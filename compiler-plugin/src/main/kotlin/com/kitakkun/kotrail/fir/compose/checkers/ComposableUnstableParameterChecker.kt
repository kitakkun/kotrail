package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.compose.stability.StabilityInferencer
import com.kitakkun.kotrail.fir.compose.stability.StableTypeMatcher
import com.kitakkun.kotrail.fir.compose.stability.StableTypeMatchers
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.types.arrayElementType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.renderReadable

/**
 * Reports a composable parameter whose type Compose treats as unstable:
 *
 * ```kotlin
 * @Composable fun UserList(users: List<User>)             // reported: List is an interface
 * @Composable fun Counter(state: Counter)                 // reported when Counter has a `var`
 * @Composable fun UserList(users: ImmutableList<User>)    // fine
 * ```
 *
 * The verdict is the Compose compiler's own stability inference (see
 * [StabilityInferencer]): with an unstable argument, Compose cannot skip the composable when the
 * argument is equal but not the same instance, and does not memoize lambdas that capture it.
 * A type that depends on a type parameter is left alone, since its stability is the caller's.
 * Overrides and inline composables are not inspected: an override cannot change its types, and
 * an inline composable is not a recomposition scope.
 */
object ComposableUnstableParameterChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_NO_UNSTABLE_PARAMETER)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.isOverride || declaration.isInline) return
        if (!declaration.symbol.isComposable(context.session)) return
        if (declaration.valueParameters.isEmpty()) return

        val matchers = StableTypeMatchers(config.compose.stableTypes.map(::StableTypeMatcher))
        val inferencer = StabilityInferencer(context.session, matchers)
        for (parameter in declaration.valueParameters) {
            val parameterSource = parameter.source ?: continue
            if (parameterSource.kind is KtFakeSourceElementKind) continue
            val declared = parameter.returnTypeRef.coneType
            val type = if (parameter.isVararg) declared.arrayElementType() ?: declared else declared
            val reason = inferencer.stabilityOf(type).unstableReason() ?: continue
            reportKotrail(
                parameterSource,
                KotrailDiagnostics.COMPOSABLE_UNSTABLE_PARAMETER,
                "${parameter.name.asString()}: ${declared.renderReadable()}",
                reason,
            )
        }
    }
}

package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.emitsUi
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.isUnit

/**
 * Keeps the trailing parameter of a UI composable free for content:
 *
 * ```kotlin
 * @Composable fun Card(title: String, modifier: Modifier = Modifier, onClick: () -> Unit)   // reported
 * @Composable fun Card(title: String, onClick: () -> Unit, modifier: Modifier = Modifier)   // fine
 * @Composable fun Card(title: String, content: @Composable () -> Unit)                      // fine
 * ```
 *
 * Call sites write the last function-type parameter as a trailing lambda, and in Compose a
 * trailing lambda reads as the composable's content slot. A plain callback there misleads the
 * reader, so it is reported on the parameter name.
 *
 * Stays quiet for composables that return a value (they are not UI emitters), for composables
 * that draw nothing (an effect wrapper such as `MutationErrorEffect(mutation, ::key) { error -> }`,
 * judged as [ComposablePreviewRequiredChecker] judges it, through [emitsUi]), for non-composable
 * functions, when the last parameter is a `@Composable` function type (nullable or not), when it
 * is not a function type at all, and for `override` / `expect` functions whose signature is
 * fixed elsewhere. Also quiet when the callback is the only function-typed parameter and no
 * parameter has a default: there is no optional block to place it before and no content-shaped
 * parameter to confuse it with, and `parameterOrder` wants the callback after the data.
 */
object ComposableTrailingCallbackChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_NO_TRAILING_CALLBACK)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.isOverride || declaration.isExpect) return
        val session = context.session
        if (!declaration.symbol.isComposable(session)) return
        if (!declaration.returnTypeRef.coneType.isUnit) return
        // An effect or a probe has no content slot for the trailing lambda to be mistaken for. A
        // declaration without a body (an interface member) cannot be judged and is still held to the rule.
        if (declaration.body != null && !declaration.symbol.emitsUi(session, config.compose.nonUiPackages)) return

        val parameters = declaration.valueParameters
        val last = parameters.lastOrNull() ?: return
        val type = last.returnTypeRef.coneType
        // `isSomeFunctionType` looks at the class behind the type, so `(() -> Unit)?` qualifies too.
        if (!type.isSomeFunctionType(session)) return
        if (type.customAnnotations.any { it.toAnnotationClassId(session) == ComposeNames.COMPOSABLE }) return
        // The only function-typed parameter, with no optional block to move it before: nothing content-shaped for it
        // to be mistaken for, and parameterOrder wants it after the data. `Card(title) { }` is what the caller writes.
        val onlyCallback = parameters.count { it.returnTypeRef.coneType.isSomeFunctionType(session) } == 1
        if (onlyCallback && parameters.none { it.defaultValue != null }) return

        val target = last.source ?: return
        reportKotrail(target, KotrailDiagnostics.COMPOSABLE_TRAILING_CALLBACK, last.name.asString())
    }
}

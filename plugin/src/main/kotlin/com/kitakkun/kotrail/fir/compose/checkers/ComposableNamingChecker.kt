package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isOperator
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isUnit

/**
 * Enforces the Compose naming convention for `@Composable` functions:
 *
 * ```kotlin
 * @Composable fun profileCard(user: User) { ... }        // emits UI: should be `ProfileCard`
 * @Composable fun RememberFormatter(): Formatter = ...   // returns a value: should be `rememberFormatter`
 * ```
 *
 * A composable that returns `Unit` emits UI and is named like a type (PascalCase); a composable
 * that returns a value behaves like a function (camelCase). Only the first character decides,
 * so all-caps names such as `FAB` are accepted for UI composables. The rule stays quiet for
 * overrides and `expect` declarations (the name is fixed elsewhere), operator functions (the
 * name is fixed by the language), and names that do not start with a letter.
 */
object ComposableNamingChecker : FirSimpleFunctionChecker(MppCheckerKind.Common) {
    private const val PASCAL_CASE = "PascalCase"
    private const val CAMEL_CASE = "camelCase"

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        if (!context.session.kotrailConfig.isEnabled(KotrailRule.COMPOSE_NAMING)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.isOverride || declaration.isExpect || declaration.isOperator) return
        if (!declaration.symbol.isComposable(context.session)) return

        val name = declaration.name
        if (name.isSpecial) return
        val text = name.asString()
        val first = text.firstOrNull() ?: return
        if (!first.isLetter()) return

        val emitsUi = declaration.returnTypeRef.coneType.isUnit
        val expected = when {
            emitsUi && first.isLowerCase() -> PASCAL_CASE
            !emitsUi && first.isUpperCase() -> CAMEL_CASE
            else -> return
        }
        reportKotrail(source, KotrailDiagnostics.COMPOSABLE_NAMING, text, expected)
    }
}

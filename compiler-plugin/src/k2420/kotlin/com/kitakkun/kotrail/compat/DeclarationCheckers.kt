package com.kitakkun.kotrail.compat

import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirNamedFunctionChecker

/**
 * The checker type for named (non-anonymous, non-accessor) functions.
 *
 * Since 2.4.20: `FirSimpleFunctionChecker` is renamed to `FirNamedFunctionChecker`, finishing
 * the `FirSimpleFunction` -> `FirNamedFunction` cleanup on the checker side. The old alias is
 * gone there, so shared code names the checker through this alias.
 */
typealias NamedFunctionChecker = FirNamedFunctionChecker

/**
 * A [DeclarationCheckers] whose named-function bucket is spelled the same on every supported
 * Kotlin version. Shared code overrides [namedFunctionCheckersCompat]; this class puts it into
 * the bucket the compiler expects.
 *
 * Since 2.4.20: the bucket is `namedFunctionCheckers`; before, `simpleFunctionCheckers`.
 */
abstract class CompatDeclarationCheckers : DeclarationCheckers() {
    abstract val namedFunctionCheckersCompat: Set<NamedFunctionChecker>

    final override val namedFunctionCheckers: Set<FirNamedFunctionChecker>
        get() = namedFunctionCheckersCompat
}

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.PublicApiScope
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.ExplicitApiMode
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.effectiveVisibility
import org.jetbrains.kotlin.fir.declarations.utils.isData
import org.jetbrains.kotlin.fir.languageVersionSettings

/**
 * Keeps data classes out of a library's public API:
 *
 * ```kotlin
 * public data class Config(val timeout: Int)     // reported
 * internal data class Config(val timeout: Int)   // fine: not part of the contract
 * public class Config(val timeout: Int)          // fine: only what is written is promised
 * ```
 *
 * A data class promises more than it shows. Its constructor, `copy()`, `componentN()`, `equals`,
 * `hashCode` and `toString` are all derived from the property list, so adding one property later
 * changes the signature of `copy()` and the constructor (a binary-incompatible change), the
 * meaning of equality, and the destructuring positions. This is the reason the Kotlin library
 * guidelines advise against exposing data classes, and the reason a generated model class is the
 * kind of thing an assistant adds to a public API without a second thought.
 *
 * A module says it is a library by compiling with explicit API mode, and by default the rule
 * applies only there (`noDataClassInPublicApi.scope=explicitApi`); `all` applies it everywhere.
 * `data object` is left alone: it has no properties for any of this to depend on.
 */
object NoDataClassInPublicApiChecker : FirRegularClassChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.NO_DATA_CLASS_IN_PUBLIC_API)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (!declaration.isData || declaration.classKind != ClassKind.CLASS) return
        if (!declaration.effectiveVisibility.publicApi) return
        if (config.noDataClassInPublicApi.scope == PublicApiScope.EXPLICIT_API && !isLibraryModule()) return

        reportKotrail(source, KotrailDiagnostics.DATA_CLASS_IN_PUBLIC_API, declaration.name.asString())
    }

    /** Explicit API mode, strict or warning, is how a module declares that its public API is a contract. */
    context(context: CheckerContext)
    private fun isLibraryModule(): Boolean =
        context.session.languageVersionSettings.getFlag(AnalysisFlags.explicitApiMode) != ExplicitApiMode.DISABLED
}

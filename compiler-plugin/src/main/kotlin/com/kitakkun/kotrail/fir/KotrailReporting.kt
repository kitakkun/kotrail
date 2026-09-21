package com.kitakkun.kotrail.fir

import com.kitakkun.kotrail.KotrailRule
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext

/**
 * Reports a Kotrail diagnostic with the severity the project configured for its rule and the
 * project's note appended to the message, unless the project's exclusion predicates match the
 * location.
 *
 * Every diagnostic knows which rule it belongs to, so a checker names neither the rule nor the
 * severity here: it says what it found and where.
 *
 * `@Suppress` with the diagnostic's base name works at either severity: the compiler matches the
 * name of the factory in use, which carries a `_WARNING` / `_ERROR` suffix when the project moved
 * the rule off its default severity, so the base name is checked here against the same set of
 * suppressed names. A suppression written for one severity survives a change of the other.
 */
context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun reportKotrail(source: KtSourceElement?, diagnostic: TunableDiagnostic0) =
    reportKotrail(source, diagnostic, emptyList())

context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun reportKotrail(source: KtSourceElement?, diagnostic: TunableDiagnostic1<String>, a: String) =
    reportKotrail(source, diagnostic, a, emptyList())

context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun reportKotrail(source: KtSourceElement?, diagnostic: TunableDiagnostic2<String, String>, a: String, b: String) =
    reportKotrail(source, diagnostic, a, b, emptyList())

/**
 * The variants with [fix]: the edits that make the diagnostic go away, applied by `kotrailFix`.
 * They are recorded only when the diagnostic is reported, and only when the compilation names a
 * `fixesDir`.
 */
context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun reportKotrail(source: KtSourceElement?, diagnostic: TunableDiagnostic0, fix: List<FixEdit>) {
    val config = context.session.kotrailConfig
    if (!shouldReport(diagnostic.baseName, diagnostic.rule)) return
    reporter.reportOn(source, diagnostic.at(config.severity(diagnostic.rule)), config.note(diagnostic.rule))
    writeFix(diagnostic.baseName, fix)
}

context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun reportKotrail(source: KtSourceElement?, diagnostic: TunableDiagnostic1<String>, a: String, fix: List<FixEdit>) {
    val config = context.session.kotrailConfig
    if (!shouldReport(diagnostic.baseName, diagnostic.rule)) return
    reporter.reportOn(source, diagnostic.at(config.severity(diagnostic.rule)), a, config.note(diagnostic.rule))
    writeFix(diagnostic.baseName, fix)
}

context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun reportKotrail(
    source: KtSourceElement?,
    diagnostic: TunableDiagnostic2<String, String>,
    a: String,
    b: String,
    fix: List<FixEdit>,
) {
    val config = context.session.kotrailConfig
    if (!shouldReport(diagnostic.baseName, diagnostic.rule)) return
    reporter.reportOn(source, diagnostic.at(config.severity(diagnostic.rule)), a, b, config.note(diagnostic.rule))
    writeFix(diagnostic.baseName, fix)
}

context(context: CheckerContext)
private fun shouldReport(baseName: String, rule: KotrailRule): Boolean =
    baseName !in context.suppressedDiagnostics && !isExcluded(rule)

context(context: CheckerContext)
private fun writeFix(diagnostic: String, fix: List<FixEdit>) {
    if (fix.isEmpty()) return
    val directory = context.session.kotrailConfig.fixesDir ?: return
    val file = context.containingFileSymbol?.sourceFile?.path ?: return
    FixRecords.write(directory, file, diagnostic, fix)
}

/** The site is only described when a predicate is configured for the rule. */
context(context: CheckerContext)
private fun isExcluded(rule: KotrailRule): Boolean {
    val excludes = context.session.kotrailConfig.excludes
    return excludes.isConfiguredFor(rule) && excludes.matches(rule, reportSite())
}

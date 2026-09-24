package com.kitakkun.kotrail.fir

import com.kitakkun.kotrail.KotrailRule
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtLightSourceElement
import org.jetbrains.kotlin.KtPsiSourceElement
import org.jetbrains.kotlin.diagnostics.AbstractKtDiagnosticFactory
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtLightDiagnosticWithParameters1
import org.jetbrains.kotlin.diagnostics.KtLightDiagnosticWithParameters2
import org.jetbrains.kotlin.diagnostics.KtLightDiagnosticWithParameters3
import org.jetbrains.kotlin.diagnostics.KtOffsetsOnlyDiagnosticWithParameters1
import org.jetbrains.kotlin.diagnostics.KtOffsetsOnlyDiagnosticWithParameters2
import org.jetbrains.kotlin.diagnostics.KtOffsetsOnlyDiagnosticWithParameters3
import org.jetbrains.kotlin.diagnostics.KtPsiDiagnosticWithParameters1
import org.jetbrains.kotlin.diagnostics.KtPsiDiagnosticWithParameters2
import org.jetbrains.kotlin.diagnostics.KtPsiDiagnosticWithParameters3
import org.jetbrains.kotlin.diagnostics.Severity
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
    val element = source ?: return
    val factory = diagnostic.at(config.severity(diagnostic.rule))
    val severity = factory.effectiveSeverity() ?: return
    val note = config.note(diagnostic.rule)
    reporter.report(
        when (element) {
            is KtPsiSourceElement -> KtPsiDiagnosticWithParameters1(element, note, severity, factory, factory.defaultPositioningStrategy, context)
            is KtLightSourceElement -> KtLightDiagnosticWithParameters1(element, note, severity, factory, factory.defaultPositioningStrategy, context)
            else -> KtOffsetsOnlyDiagnosticWithParameters1(element, note, severity, factory, factory.defaultPositioningStrategy, context)
        },
        context,
    )
    writeFix(diagnostic.rule, diagnostic.baseName, fix)
}

context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun reportKotrail(source: KtSourceElement?, diagnostic: TunableDiagnostic1<String>, a: String, fix: List<FixEdit>) {
    val config = context.session.kotrailConfig
    if (!shouldReport(diagnostic.baseName, diagnostic.rule)) return
    val element = source ?: return
    val factory = diagnostic.at(config.severity(diagnostic.rule))
    val severity = factory.effectiveSeverity() ?: return
    val note = config.note(diagnostic.rule)
    reporter.report(
        when (element) {
            is KtPsiSourceElement -> KtPsiDiagnosticWithParameters2(element, a, note, severity, factory, factory.defaultPositioningStrategy, context)
            is KtLightSourceElement -> KtLightDiagnosticWithParameters2(element, a, note, severity, factory, factory.defaultPositioningStrategy, context)
            else -> KtOffsetsOnlyDiagnosticWithParameters2(element, a, note, severity, factory, factory.defaultPositioningStrategy, context)
        },
        context,
    )
    writeFix(diagnostic.rule, diagnostic.baseName, fix)
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
    val element = source ?: return
    val factory = diagnostic.at(config.severity(diagnostic.rule))
    val severity = factory.effectiveSeverity() ?: return
    val note = config.note(diagnostic.rule)
    reporter.report(
        when (element) {
            is KtPsiSourceElement -> KtPsiDiagnosticWithParameters3(element, a, b, note, severity, factory, factory.defaultPositioningStrategy, context)
            is KtLightSourceElement -> KtLightDiagnosticWithParameters3(element, a, b, note, severity, factory, factory.defaultPositioningStrategy, context)
            else -> KtOffsetsOnlyDiagnosticWithParameters3(element, a, b, note, severity, factory, factory.defaultPositioningStrategy, context)
        },
        context,
    )
    writeFix(diagnostic.rule, diagnostic.baseName, fix)
}

/**
 * The severity the diagnostic is reported at. A Kotrail warning is a decision the project wrote
 * down (`severity: warning`, or a rule that defaults to it), so it is reported as a fixed
 * warning, which `-Werror` / `allWarningsAsErrors` does not promote: a project that treats the
 * compiler's warnings as errors can still adopt a rule gradually. The compiler's own
 * `-Xwarning-level=<name>:error|warning|disabled` still applies on top, by the factory's name.
 */
context(context: CheckerContext)
private fun AbstractKtDiagnosticFactory.effectiveSeverity(): Severity? =
    when (val effective = getEffectiveSeverity(context.languageVersionSettings)) {
        Severity.WARNING -> Severity.FIXED_WARNING
        else -> effective
    }

context(context: CheckerContext)
private fun shouldReport(baseName: String, rule: KotrailRule): Boolean =
    baseName !in context.suppressedDiagnostics && !isGenerated() && !isExcluded(rule)

/**
 * Whether the current site is generated code, by the file's path (`generated.paths`) or by an
 * annotation on the file or an enclosing declaration (`generated.annotations`); every rule
 * skips it.
 */
context(context: CheckerContext)
internal fun isGenerated(): Boolean {
    val generated = context.session.kotrailConfig.generated
    val path = context.containingFileSymbol?.sourceFile?.path
    if (path != null && generated.matchesPath(path)) return true
    return generated.annotations.isNotEmpty() && generated.matchesAnnotations(reportSite().annotations)
}

context(context: CheckerContext)
private fun writeFix(rule: KotrailRule, diagnostic: String, fix: List<FixEdit>) {
    if (fix.isEmpty()) return
    val config = context.session.kotrailConfig
    if (!config.fixEnabled(rule)) return
    val directory = config.fixesDir ?: return
    val file = context.containingFileSymbol?.sourceFile?.path ?: return
    FixRecords.write(directory, file, diagnostic, fix)
}

/**
 * Whether a finding at the current site would be dropped: one of [baseNames] is suppressed here,
 * or the rule's exclusion predicate matches. For a checker that records a finding for another
 * compilation to report, so that an opt-out written at the site holds there too.
 */
context(context: CheckerContext)
internal fun isSuppressedOrExcluded(baseNames: List<String>, rule: KotrailRule): Boolean =
    baseNames.any { it in context.suppressedDiagnostics } || isGenerated() || isExcluded(rule)

/** The site is only described when a predicate is configured for the rule. */
context(context: CheckerContext)
private fun isExcluded(rule: KotrailRule): Boolean {
    val excludes = context.session.kotrailConfig.excludes
    return excludes.isConfiguredFor(rule) && excludes.matches(rule, reportSite())
}

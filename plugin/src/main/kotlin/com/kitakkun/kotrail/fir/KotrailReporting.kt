package com.kitakkun.kotrail.fir

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext

/**
 * Reports a Kotrail diagnostic with the severity the project configured for its rule, and the
 * project's note appended to the message.
 *
 * Every diagnostic knows which rule it belongs to, so a checker names neither the rule nor the
 * severity here: it says what it found and where.
 */
context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun reportKotrail(source: KtSourceElement?, diagnostic: TunableDiagnostic0) {
    val config = context.session.kotrailConfig
    reporter.reportOn(source, diagnostic.at(config.severity(diagnostic.rule)), config.note(diagnostic.rule))
}

context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun reportKotrail(source: KtSourceElement?, diagnostic: TunableDiagnostic1<String>, a: String) {
    val config = context.session.kotrailConfig
    reporter.reportOn(source, diagnostic.at(config.severity(diagnostic.rule)), a, config.note(diagnostic.rule))
}

context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun reportKotrail(
    source: KtSourceElement?,
    diagnostic: TunableDiagnostic2<String, String>,
    a: String,
    b: String,
) {
    val config = context.session.kotrailConfig
    reporter.reportOn(source, diagnostic.at(config.severity(diagnostic.rule)), a, b, config.note(diagnostic.rule))
}

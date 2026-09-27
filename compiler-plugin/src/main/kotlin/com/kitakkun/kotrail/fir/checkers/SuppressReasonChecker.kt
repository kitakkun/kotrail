package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.comments.SuppressedIds
import com.kitakkun.kotrail.comments.SuppressionReason
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirBasicDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.text
import org.jetbrains.kotlin.types.ConstantValueKind

/**
 * Reports a suppression annotation that carries no written reason:
 *
 * ```kotlin
 * @Suppress("KOTRAIL_CATCH_TOO_BROAD")                       // reported
 * fun poll() { ... }
 *
 * // The SDK throws its own base type; anything else here is a bug in the SDK.
 * @Suppress("KOTRAIL_CATCH_TOO_BROAD")                       // fine
 * fun poll() { ... }
 *
 * @Suppress("UNCHECKED_CAST") // the map is built above with String keys   // fine
 *
 * @Suppress("unused")                                       // fine: exempt
 * ```
 *
 * A suppression silences a check for every later reader, who then has to guess why; the reason
 * belongs next to the annotation. The annotations checked are `suppressReason.annotations`
 * (`kotlin.Suppress` and `android.annotation.SuppressLint`), on any declaration and on the file.
 * Every suppression owes a reason (`suppressReason.ids` is `*`) except those whose ids all match
 * `suppressReason.exemptIds` (`unused`, `UNUSED`, `UNUSED_*`: a member invoked through a framework carries
 * `@Suppress("unused")` for a reason that is always the same); an annotation that mixes an exempt
 * id with another is checked, as decided by [SuppressedIds]. Whether a reason is there is decided
 * from the file's text by [SuppressionReason]: a comment on the annotation's line or the line
 * above, or a comment above the group of annotated declarations it belongs to.
 */
object SuppressReasonChecker : FirBasicDeclarationChecker(MppCheckerKind.Common) {
    private val STRING_LITERAL = Regex("\"([^\"\\\\]*)\"")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirDeclaration) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.SUPPRESS_REASON)) return
        if (declaration.annotations.isEmpty()) return
        val source = declaration.source ?: return
        val kind = source.kind
        // A property made from a constructor parameter shares the parameter's annotations, and a
        // default accessor holds the `@get:` / `@set:` annotations written on its property: on
        // those, only the targeted annotations are their own.
        val onlyTargeted = kind is KtFakeSourceElementKind.PropertyFromParameter || kind is KtFakeSourceElementKind.DefaultAccessor
        if (kind is KtFakeSourceElementKind && !onlyTargeted) return
        val text = ((declaration as? FirFile)?.source ?: context.containingFileSymbol?.source)?.text ?: return
        val settings = config.suppressReason
        val checked = Checked(settings.annotations, SuppressedIds(settings.ids, settings.exemptIds))

        for (annotation in declaration.annotations) {
            val at = annotation.source ?: continue
            // A `@get:` annotation reaches the accessor as a copy with a fake source that keeps the written offsets.
            if (at.kind is KtFakeSourceElementKind && at.kind !is KtFakeSourceElementKind.FromUseSiteTarget) continue
            val target = annotation.useSiteTarget
            if (onlyTargeted && (target == null || target == AnnotationUseSiteTarget.CONSTRUCTOR_PARAMETER)) continue
            if (!checked.matches(annotation, context.session)) continue
            if (SuppressionReason.hasReason(text, at.startOffset, at.endOffset, checked::lineCarriesOne)) continue
            val written = at.text?.toString()?.replace(Regex("\\s+"), " ") ?: continue
            reportKotrail(at, KotrailDiagnostics.SUPPRESS_WITHOUT_REASON, written)
        }
    }

    /** The configured annotations and ids, with the two ways of recognizing them: on a FIR annotation and on a line of text. */
    private class Checked(annotations: List<String>, private val ids: SuppressedIds) {
        private val classIds = annotations.map { ClassId.topLevel(FqName(it)) }.toSet()
        private val shortNames = annotations.map { it.substringAfterLast('.') }.toSet()
        private val onLine = Regex("@(?:\\w+:)?(?:[\\w.]+\\.)?(?:" + shortNames.joinToString("|") { Regex.escape(it) } + ")\\b")

        fun matches(annotation: FirAnnotation, session: FirSession): Boolean {
            val classId = annotation.toAnnotationClassIdSafe(session)
            val named = if (classId != null) classId in classIds else annotation.shortName() in shortNames
            return named && ids.owesReason(stringArguments(annotation))
        }

        fun lineCarriesOne(line: String): Boolean =
            onLine.containsMatchIn(line) && ids.owesReason(STRING_LITERAL.findAll(line).map { it.groupValues[1] }.toList())

        /** An annotation the compiler could not resolve (a platform annotation missing from the classpath) is known by its written name. */
        private fun FirAnnotation.shortName(): String? =
            annotationTypeRef.source?.text?.toString()?.substringAfterLast('.')?.substringBefore('<')?.trim()

        private fun stringArguments(annotation: FirAnnotation): List<String> {
            val found = mutableListOf<String>()
            val collector = object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (element is FirLiteralExpression && element.kind == ConstantValueKind.String) found += element.value.toString()
                    element.acceptChildren(this)
                }
            }
            for (argument in annotation.argumentMapping.mapping.values) argument.accept(collector)
            if (found.isEmpty() && annotation is FirAnnotationCall) for (argument in annotation.argumentList.arguments) argument.accept(collector)
            return found
        }
    }
}

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.FixEdit
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.EffectiveVisibility
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirBasicDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.utils.effectiveVisibility
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.jvm.isJvm

/**
 * Asks for `@JvmSynthetic` on `internal` functions and properties of a JVM compilation:
 *
 * ```kotlin
 * internal fun reset() { }                        // reported: public to Java, as reset$module
 * @JvmSynthetic internal fun reset() { }          // fine: Java cannot name it
 * internal val cache: Cache = ...                 // reported: @get:JvmSynthetic
 * ```
 *
 * The JVM has no `internal`. The compiler keeps other Kotlin modules out, but a Java caller sees
 * a public member, only its name mangled with the module's, and calls it. `@JvmSynthetic` marks
 * the member synthetic in bytecode, which the Java compiler ignores, so the declaration is as
 * hidden from Java as it is from Kotlin. Nothing hides an `internal` class the same way, since a
 * class cannot be synthetic; those are reported under their own diagnostic so that a library
 * knows what it publishes. Off by default: it is for a module whose consumers include Java.
 *
 * Left alone: declarations whose effective visibility is narrower than internal (a member of a
 * private class), interface members (Java interfaces cannot carry synthetic methods), `expect`
 * declarations, and non-JVM compilations.
 */
object JvmSyntheticForInternalChecker : FirBasicDeclarationChecker(MppCheckerKind.Common) {
    private val JVM_SYNTHETIC = ClassId(FqName("kotlin.jvm"), Name.identifier("JvmSynthetic"))

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirDeclaration) {
        val session = context.session
        if (!session.kotrailConfig.isEnabled(KotrailRule.JVM_SYNTHETIC_FOR_INTERNAL)) return
        if (!session.moduleData.platform.isJvm()) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return

        when (declaration) {
            is FirRegularClass -> {
                if (declaration.visibility != Visibilities.Internal || declaration.effectiveVisibility != EffectiveVisibility.Internal) return
                reportKotrail(source, KotrailDiagnostics.INTERNAL_CLASS_VISIBLE_TO_JAVA, declaration.name.asString())
            }
            is FirNamedFunction -> {
                if (!declaration.isInternalOnJvm(session)) return
                if (declaration.symbol.hasAnnotation(JVM_SYNTHETIC, session)) return
                reportKotrail(source, KotrailDiagnostics.INTERNAL_VISIBLE_TO_JAVA, declaration.name.asString(), "@JvmSynthetic", listOf(FixEdit(source.startOffset, source.startOffset, "@JvmSynthetic ")))
            }
            is FirProperty -> {
                if (declaration.isLocal || !declaration.isInternalOnJvm(session)) return
                val needed = buildList {
                    if (declaration.getter?.symbol?.hasAnnotation(JVM_SYNTHETIC, session) != true) add("@get:JvmSynthetic")
                    val setter = declaration.setter
                    if (declaration.isVar && setter != null && setter.visibility != Visibilities.Private && !setter.symbol.hasAnnotation(JVM_SYNTHETIC, session)) add("@set:JvmSynthetic")
                }
                if (needed.isEmpty()) return
                val wanted = needed.joinToString(" ")
                reportKotrail(source, KotrailDiagnostics.INTERNAL_VISIBLE_TO_JAVA, declaration.name.asString(), wanted, listOf(FixEdit(source.startOffset, source.startOffset, "$wanted ")))
            }
            else -> {}
        }
    }

    /** Declared `internal`, reachable as such (not inside something narrower), and able to carry the annotation. */
    context(context: CheckerContext)
    private fun FirCallableDeclaration.isInternalOnJvm(session: FirSession): Boolean {
        if (visibility != Visibilities.Internal || isExpect) return false
        if (effectiveVisibility != EffectiveVisibility.Internal) return false
        val owner = context.containingDeclarations.lastOrNull { it is FirClassSymbol<*> } as? FirClassSymbol<*>
        return owner?.classKind != ClassKind.INTERFACE
    }
}

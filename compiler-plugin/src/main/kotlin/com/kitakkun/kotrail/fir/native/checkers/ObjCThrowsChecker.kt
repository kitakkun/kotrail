package com.kitakkun.kotrail.fir.native.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.native.throwsService
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.utils.effectiveVisibility
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Reports a public function of an Apple framework that can let an exception out without
 * declaring `@Throws`:
 *
 * ```kotlin
 * fun parse(text: String): Config {
 *     require(text.isNotBlank())            // reported: IllegalArgumentException reaches Swift undeclared
 *     ...
 * }
 *
 * @Throws(IllegalArgumentException::class)
 * fun parse(text: String): Config { ... }  // fine: Swift receives an NSError
 * ```
 *
 * An exception that crosses into Swift or Objective-C from a function without `@Throws`
 * terminates the process; with it, the exception arrives as an `NSError` the caller can handle.
 * What a function lets out is decided by [com.kitakkun.kotrail.fir.native.ThrowsService]: a
 * `throw`, a precondition, a call to a `@Throws` function, or a call to a function of this module
 * that lets one out, none of them under a `try` that catches it. Only Apple native compilations
 * are checked (where `kotlinx.cinterop.ObjCObject` resolves), and only what the framework
 * exports: public API, not `@HiddenFromObjC`, not an override (its signature is fixed elsewhere),
 * not an inline function with a reified type parameter (which has no Objective-C entry point).
 * `native.objcThrows.packages` narrows it to the packages a framework actually exports.
 */
object ObjCThrowsChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    private val HIDDEN_FROM_OBJC = ClassId(FqName("kotlin.native"), Name.identifier("HiddenFromObjC"))

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val session = context.session
        if (!session.kotrailConfig.isEnabled(KotrailRule.NATIVE_OBJC_THROWS)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (session.symbolProvider.getClassLikeSymbolByClassId(ObjCNames.OBJC_OBJECT) == null) return
        if (!declaration.effectiveVisibility.publicApi || declaration.isOverride || declaration.body == null) return
        if (declaration.name.asString() == "main") return
        // An inline function with a reified type parameter has no Objective-C entry point at all.
        if (declaration.typeParameters.any { it.isReified }) return
        val packages = session.kotrailConfig.objcThrows.packages
        if (packages.isNotEmpty()) {
            val packageName = declaration.symbol.callableId.packageName.asString()
            if (packages.none { it.matches(packageName) }) return
        }
        val service = session.throwsService
        if (service.declaredThrows(declaration.symbol) != null) return
        if (declaration.symbol.hasAnnotation(HIDDEN_FROM_OBJC, session)) return
        if (context.containingDeclarations.any { it is FirRegularClassSymbol && it.hasAnnotation(HIDDEN_FROM_OBJC, session) }) return
        val thrown = service.thrown(declaration.symbol) ?: return
        reportKotrail(source, KotrailDiagnostics.OBJC_EXPORT_MISSING_THROWS, declaration.name.asString(), thrown.description)
    }
}

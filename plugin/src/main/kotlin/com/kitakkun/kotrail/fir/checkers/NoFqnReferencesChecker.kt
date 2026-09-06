@file:OptIn(SymbolInternals::class, DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirQualifiedAccessExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirResolvedQualifierChecker
import org.jetbrains.kotlin.fir.analysis.checkers.type.FirTypeRefChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.scopes.defaultImportsProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.abbreviatedTypeOrSelf
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.text

/**
 * Asks for an import instead of a fully qualified reference:
 *
 * ```kotlin
 * val id = java.util.UUID.randomUUID()     // -> import java.util.UUID; UUID.randomUUID()
 * val file: java.io.File = ...             // -> import java.io.File; File
 * kotlin.io.println("x")                   // -> println("x")
 * ```
 *
 * Nothing is reported when a plain import would not work: the simple name is already bound to
 * something else through an explicit import, a star import, a default import, a same-package
 * declaration, or a declaration in the same file. Packages listed in
 * `noFqnReferences.allow` are exempt.
 */
object NoFqnReferences {
    /** `java.util.UUID.randomUUID()`, `java.util.UUID` as a value, `java.util.Map.Entry` in expressions. */
    object QualifierChecker : FirResolvedQualifierChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirResolvedQualifier) {
            if (!expression.isWrittenWithPackage()) return
            val classId = expression.classId ?: return
            // Report the first class in a chain like `java.util.Map.Entry` once.
            if (expression.explicitParent?.classId != null) return
            reportClass(expression.source, classId)
        }
    }

    /** `kotlin.io.println("x")`, `kotlin.math.PI`, `java.util.Date()`: reached through a package qualifier. */
    object CallableChecker : FirQualifiedAccessExpressionChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirQualifiedAccessExpression) {
            val qualifier = expression.explicitReceiver as? FirResolvedQualifier ?: return
            if (!qualifier.isWrittenWithPackage() || qualifier.classId != null) return
            val callee = expression.calleeReference.toResolvedCallableSymbol() ?: return
            val callableId = callee.callableId ?: return
            if (callee is FirConstructorSymbol) {
                // `java.util.Date()`: the package qualifier carries the class into the constructor call.
                reportClass(qualifier.source, callableId.classId ?: return)
                return
            }
            if (callableId.classId != null) return
            val config = context.session.kotrailConfig
            if (!config.isEnabled(KotrailRule.NO_FQN_REFERENCES)) return
            if (isAllowed(callableId.packageName, config.noFqnReferences.allow)) return
            val file = context.containingFileSymbol?.fir ?: return
            if (callableNameIsTaken(callableId.packageName, callableId.callableName, file, context.session)) return
            val source = qualifier.source ?: return
            if (source.kind is KtFakeSourceElementKind) return
            reporter.reportOn(
                source,
                KotrailDiagnostics.FQN_REFERENCE.at(config.severity(KotrailRule.NO_FQN_REFERENCES)),
                "import ${callableId.packageName.asString()}.${callableId.callableName.asString()}",
            )
        }
    }

    /** `val f: java.io.File`, `List<java.io.File>`, `x is java.io.File`. */
    object TypeChecker : FirTypeRefChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(typeRef: FirTypeRef) {
            val resolved = typeRef as? FirResolvedTypeRef ?: return
            val written = (resolved.delegatedTypeRef as? FirUserTypeRef)?.qualifier?.map { it.name } ?: return
            if (written.size < 2) return
            val classId = resolved.coneType.abbreviatedTypeOrSelf.classId ?: return
            val classSegments = classId.relativeClassName.pathSegments().size
            if (written.size <= classSegments) return
            val writtenPackage = FqName.fromSegments(written.dropLast(classSegments).map { it.asString() })
            if (writtenPackage != classId.packageFqName) return
            reportClass(typeRef.source, classId)
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportClass(source: KtSourceElement?, classId: ClassId) {
        if (source == null || source.kind is KtFakeSourceElementKind) return
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.NO_FQN_REFERENCES)) return
        if (isAllowed(classId.packageFqName, config.noFqnReferences.allow)) return
        val outermost = classId.outermostClassId
        val file = context.containingFileSymbol?.fir ?: return
        if (classNameIsTaken(outermost, file, context.session)) return
        val importFqName = outermost.asSingleFqName().asString()
        val usage = classId.relativeClassName.asString()
        reporter.reportOn(
            source,
            KotrailDiagnostics.FQN_REFERENCE.at(config.severity(KotrailRule.NO_FQN_REFERENCES)),
            "import $importFqName and write $usage",
        )
    }

    /** Whether the qualifier was spelled with its package (`java.util.UUID`, `kotlin.io`) rather than a simple name. */
    private fun FirResolvedQualifier.isWrittenWithPackage(): Boolean {
        if (packageFqName.isRoot) return false
        val written = source.text?.toString()?.filterNot { it.isWhitespace() } ?: return false
        val prefix = packageFqName.asString()
        return written == prefix || written.startsWith("$prefix.")
    }

    private fun isAllowed(packageFqName: FqName, allow: List<String>): Boolean {
        val name = packageFqName.asString()
        return allow.any { prefix -> name == prefix || name.startsWith("$prefix.") }
    }

    /** Whether the simple name of [target] already means something else in [file]. */
    private fun classNameIsTaken(target: ClassId, file: FirFile, session: FirSession): Boolean {
        val name = target.shortClassName
        for (import in file.imports) {
            val fqName = import.importedFqName ?: continue
            if (import.isAllUnder) {
                val candidate = ClassId(fqName, name)
                if (candidate != target && session.symbolProvider.getClassLikeSymbolByClassId(candidate) != null) return true
            } else {
                val bound = import.aliasName ?: fqName.shortName()
                if (bound == name && fqName != target.asSingleFqName()) return true
            }
        }
        if (file.declarations.any { it is FirClassLikeDeclaration && it.symbol.name == name && it.symbol.classId != target }) return true
        val filePackage = file.packageDirective.packageFqName
        val samePackage = ClassId(filePackage, name)
        if (samePackage != target && session.symbolProvider.getClassLikeSymbolByClassId(samePackage) != null) return true
        for (path in session.defaultImportsProvider.getDefaultImports(includeLowPriorityImports = true)) {
            if (path.isAllUnder) {
                val candidate = ClassId(path.fqName, name)
                if (candidate != target && session.symbolProvider.getClassLikeSymbolByClassId(candidate) != null) return true
            } else if ((path.alias ?: path.fqName.shortName()) == name && path.fqName != target.asSingleFqName()) {
                return true
            }
        }
        return false
    }

    /** Whether a top-level callable named [name] from another package is already in scope in [file]. */
    private fun callableNameIsTaken(targetPackage: FqName, name: Name, file: FirFile, session: FirSession): Boolean {
        fun providesCallable(packageFqName: FqName): Boolean =
            packageFqName != targetPackage &&
                (session.symbolProvider.getTopLevelFunctionSymbols(packageFqName, name).isNotEmpty() ||
                    session.symbolProvider.getTopLevelPropertySymbols(packageFqName, name).isNotEmpty())

        for (import in file.imports) {
            val fqName = import.importedFqName ?: continue
            if (import.isAllUnder) {
                if (providesCallable(fqName)) return true
            } else {
                val bound = import.aliasName ?: fqName.shortName()
                if (bound == name && fqName.parent() != targetPackage) return true
            }
        }
        if (file.declarations.any { it is FirCallableDeclaration && it.symbol.callableId?.callableName == name }) return true
        if (providesCallable(file.packageDirective.packageFqName)) return true
        for (path in session.defaultImportsProvider.getDefaultImports(includeLowPriorityImports = true)) {
            if (path.isAllUnder) {
                if (providesCallable(path.fqName)) return true
            } else if ((path.alias ?: path.fqName.shortName()) == name && path.fqName.parent() != targetPackage) {
                return true
            }
        }
        return false
    }
}

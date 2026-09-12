@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir

import com.kitakkun.kotrail.exclude.ReportSite
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.test.isTestFunction
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId

/**
 * Describes where a diagnostic is being reported, for the exclusion predicates.
 *
 * The enclosing declarations come from the checker context; the declaration currently being
 * checked is not among them, so it is taken from the innermost element under visit when that is a
 * named declaration. Nothing is resolved here that the checkers have not resolved already.
 */
context(context: CheckerContext)
internal fun reportSite(): ReportSite {
    val session = context.session
    val file = context.containingFileSymbol?.fir
    val chain = declarationChain()
    val innermost = chain.lastOrNull()
    val callable = innermost as? FirCallableSymbol<*>
    val function = innermost as? FirNamedFunctionSymbol

    return ReportSite(
        packageName = file?.packageDirective?.packageFqName?.asString().orEmpty(),
        fileName = file?.name.orEmpty(),
        declarationName = when (innermost) {
            is FirCallableSymbol<*> -> innermost.name.asString()
            is FirClassSymbol<*> -> innermost.classId.shortClassName.asString()
            else -> null
        },
        classNames = chain.filterIsInstance<FirClassSymbol<*>>().asReversed().map { it.classId.shortClassName.asString() },
        annotations = chain.flatMapTo(HashSet()) { symbol -> symbol.annotationNames(session) },
        extensionReceiver = callable?.resolvedReceiverType?.fqName(),
        contextParameters = callable?.contextParameterSymbols?.mapNotNull { it.resolvedReturnType.fqName() }.orEmpty(),
        visibility = callable?.visibility?.name ?: (innermost as? FirClassSymbol<*>)?.visibility?.name,
        isOverride = callable?.isOverride == true,
        isSuspend = function?.isSuspend == true,
        isInline = function?.isInline == true,
        isComposable = function?.isComposable(session) == true,
        isTest = function?.isTestFunction(session, session.kotrailConfig.test.annotations) == true,
    )
}

/** Enclosing declarations outermost first, ending with the declaration under check when there is one. */
context(context: CheckerContext)
private fun declarationChain(): List<FirBasedSymbol<*>> {
    val chain = context.containingDeclarations.filter {
        it is FirNamedFunctionSymbol || it is FirPropertySymbol || it is FirClassSymbol<*>
    }.toMutableList()
    val current = context.containingElements.lastOrNull { it is FirNamedFunction || it is FirProperty || it is FirRegularClass } as? FirDeclaration
    if (current != null && chain.lastOrNull() != current.symbol) chain += current.symbol
    return chain
}

private fun FirBasedSymbol<*>.annotationNames(session: FirSession): List<String> =
    resolvedAnnotationsWithArguments.mapNotNull { it.toAnnotationClassId(session)?.asSingleFqName()?.asString() }

private fun ConeKotlinType.fqName(): String? = classId?.asSingleFqName()?.asString()

package com.kitakkun.kotrail.fir

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.lookupTracker
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Tells Kotlin's incremental compilation that the file being checked asked whether [name] exists
 * in [scope] (a package, or a class by its fully qualified name), as the compiler's own resolution
 * does for every name it looks up. A checker that asks by hand (a symbol provider, a class's
 * scope) leaves no such record, and incremental compilation would not compile the file again when
 * that name appears in, or goes from, another file: the finding the answer led to would stay, or
 * stay missing. A no-op outside incremental compilation, which has no lookup tracker.
 */
fun FirSession.recordNameProbe(name: Name, scope: FqName, source: KtSourceElement?, fileSource: KtSourceElement?) {
    val tracker = lookupTracker ?: return
    tracker.recordLookup(name.asString(), scope.asString(), source, fileSource)
}

/** [recordNameProbe] for the file [context] checks. */
context(context: CheckerContext)
fun recordNameProbe(name: Name, scope: FqName, source: KtSourceElement?) {
    context.session.recordNameProbe(name, scope, source, context.containingFileSymbol?.source)
}

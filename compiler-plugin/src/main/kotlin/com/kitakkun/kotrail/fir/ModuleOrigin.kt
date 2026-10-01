package com.kitakkun.kotrail.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol

/**
 * Whether the declaration belongs to the module [session] compiles without being compiled from
 * source this time. Under JVM incremental compilation the declarations of the files the compiler
 * does not compile again are loaded back from the output directory, without bodies, like a
 * library's. Only the classes and the top-level functions among them get the origin
 * `FirDeclarationOrigin.Precompiled`; the members of those classes and the top-level properties
 * are deserialized as `Library`, as a library's are. What tells those apart from a library is
 * their module data: the provider that loads them gives every declaration the data of the module
 * being compiled (in a multiplatform compilation, that of the first source set it serves, which
 * the others depend on), where a library's declarations carry the library's.
 *
 * A fake override (a substitution or intersection override) is neither: its module data is the
 * use site's, whatever it overrides.
 */
fun FirBasedSymbol<*>.isPrecompiled(session: FirSession): Boolean {
    val origin = origin
    if (origin == FirDeclarationOrigin.Precompiled) return true
    if (origin != FirDeclarationOrigin.Library) return false
    val own = session.moduleData
    return moduleData == own || moduleData in own.allDependsOnDependencies
}

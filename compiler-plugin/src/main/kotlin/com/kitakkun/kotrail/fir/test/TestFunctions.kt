package com.kitakkun.kotrail.fir.test

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol

/**
 * Whether this function is a test, according to the annotations the project listed in
 * `test.annotations`.
 *
 * Test frameworks are recognized by annotation rather than by source set: the compiler sees one
 * compilation at a time and cannot tell a test source set from a main one, and matching the
 * annotation also keeps the test rules quiet for spec-style frameworks (Kotest, Spek), whose
 * cases are lambdas rather than annotated functions.
 */
fun FirNamedFunctionSymbol.isTestFunction(session: FirSession, annotations: List<String>): Boolean {
    if (annotations.isEmpty()) return false
    return resolvedAnnotationsWithArguments.any { annotation ->
        annotation.toAnnotationClassId(session)?.asSingleFqName()?.asString() in annotations
    }
}

private val TEST_SOURCE_PATH = Regex("(^|/)src/(test[^/]*|[^/]*Test[^/]*)/")

/**
 * Whether [path] lies in a test source set by the Gradle layout: `src/test/`, `src/testFixtures/`,
 * `src/jvmTest/`, `src/androidUnitTest/` and the like. For rules about production code only,
 * where a test is expected to reach into what it checks.
 */
fun isTestSourcePath(path: String): Boolean = TEST_SOURCE_PATH.containsMatchIn(path.replace('\\', '/'))

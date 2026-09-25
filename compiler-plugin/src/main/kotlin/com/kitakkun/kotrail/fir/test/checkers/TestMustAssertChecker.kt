package com.kitakkun.kotrail.fir.test.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.exclude.Glob
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.test.isTestFunction
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirImplicitInvokeCall
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Reports a test that asserts nothing:
 *
 * ```kotlin
 * @Test fun `loads items`() = runTest {
 *     viewModel.load()                        // reported: passes as long as nothing throws
 * }
 *
 * @Test fun `loads items`() = runTest {
 *     viewModel.load()
 *     assertEquals(3, viewModel.items.size)   // fine
 * }
 * ```
 *
 * A test without an assertion only proves that the code did not throw, which is rarely what its
 * name promises, and it keeps passing when the behavior it was written for is lost. What counts
 * as an assertion is `test.mustAssert.assertions`: globs over fully qualified callables, matched
 * against the callee's full name and, for the `*.assert*` style entries, against its short name
 * as well, so that a project's own `verifyState()` helper counts. Calls into the test's own
 * helpers (a private function, or a member of the test's class) are followed, so an assertion
 * inside a helper satisfies the test. A test annotation that names an expected exception
 * (`@Test(expected = ...)`) is an assertion too. Which annotations mark a test is
 * `test.annotations`.
 */
object TestMustAssertChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    private val EXPECTED = Name.identifier("expected")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.TEST_MUST_ASSERT)) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (!declaration.symbol.isTestFunction(context.session, config.test.annotations)) return
        val body = declaration.body ?: return
        if (declaration.expectsException()) return

        val finder = AssertionFinder(
            assertions = config.test.assertions.map(::Glob),
            testClass = declaration.symbol.callableId?.classId,
        )
        body.accept(finder)
        if (finder.found) return
        reportKotrail(source, KotrailDiagnostics.TEST_WITHOUT_ASSERTION, declaration.name.asString())
    }

    /** `@Test(expected = SomeException::class)`: the test asserts by its annotation. */
    private fun FirNamedFunction.expectsException(): Boolean =
        annotations.any { annotation -> annotation.argumentMapping.mapping.keys.any { it == EXPECTED } }

    /**
     * Looks for an assertion in a body. A call to one of the test's own helpers is followed into
     * the helper's body, each helper once, so that a test asserting through a helper counts.
     */
    private class AssertionFinder(
        private val assertions: List<Glob>,
        private val testClass: ClassId?,
    ) : FirVisitorVoid() {
        var found = false
            private set
        private val visited = mutableSetOf<FirNamedFunctionSymbol>()

        override fun visitElement(element: FirElement) {
            if (found) return
            element.acceptChildren(this)
        }

        // FirVisitorVoid routes an implicit invoke to visitElement, not to visitFunctionCall.
        override fun visitImplicitInvokeCall(implicitInvokeCall: FirImplicitInvokeCall) {
            visitFunctionCall(implicitInvokeCall)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            if (found) return
            val callee = functionCall.calleeReference.toResolvedNamedFunctionSymbol()
            if (callee != null) {
                if (callee.isAssertion()) {
                    found = true
                    return
                }
                if (callee.isOwnHelper()) followInto(callee)
                if (found) return
            }
            functionCall.acceptChildren(this)
        }

        private fun FirNamedFunctionSymbol.isAssertion(): Boolean {
            val fqn = callableId?.asSingleFqName()?.asString() ?: return false
            val short = name.asString()
            return assertions.any { it.matches(fqn) || it.matches(short) }
        }

        /** A private function, or a member of the test's own class: helpers that belong to this test file. */
        private fun FirNamedFunctionSymbol.isOwnHelper(): Boolean {
            if (!origin.fromSource) return false
            if (visibility == Visibilities.Private) return true
            return testClass != null && callableId?.classId == testClass
        }

        @OptIn(SymbolInternals::class)
        private fun followInto(helper: FirNamedFunctionSymbol) {
            if (!visited.add(helper)) return
            helper.fir.body?.accept(this)
        }
    }
}

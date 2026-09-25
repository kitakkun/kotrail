# Test must assert

**Diagnostic:** `KOTRAIL_TEST_WITHOUT_ASSERTION` (error, on the test's name)
**Key:** `rules.test.mustAssert` (on by default)
**Settings:** `test.annotations`, `assertions` (default: kotlin.test, JUnit, assertk, kotest, Truth, the `verify` functions of Mokkery, MockK and Mockito, and the name patterns `*.assert*`, `*.verify*`, `*.expect*`, `*.should*`)

## What it rejects

A test that asserts nothing:

```kotlin
@Test fun `loads items`() = runTest {
    viewModel.load()                        // reported: passes as long as nothing throws
}
```

## What it asks for

```kotlin
@Test fun `loads items`() = runTest {
    viewModel.load()
    assertEquals(3, viewModel.items.size)
}
```

or a verified interaction (`verify { repository.fetch() }`), or an expected failure
(`assertFailsWith<IllegalStateException> { ... }`, `@Test(expected = ...)`). A test without an
assertion proves only that the code did not throw, which is rarely what its name promises, and
it keeps passing when the behavior it was written for is lost. A generated test suite is the
usual source: the shape is right, the check was never written.

## How an assertion is recognized

`assertions` is a list of globs over fully qualified callables. Each is matched against the
callee's full name (`kotlin.test.assertEquals`) and against its short name (`assertEquals`), so
the name patterns in the default list cover a project's own helpers: a `verifyState()` or
`expectItems(3)` counts. Infix assertions (`result shouldBe 3`) are ordinary calls and match
`*.should*`. A project with its own assertion library replaces the list:

```yaml
rules:
  test.mustAssert:
    assertions:
      - kotlin.test.*
      - com.acme.testing.check*
```

Which functions are tests is `test.annotations`, shared with [test naming](naming.md).

## Assertions in helpers

Calls from a test into its own helpers are followed: a `private` function, or any member of the
test's class. An assertion inside the helper satisfies the test, so a suite that funnels its
checks through `assertState(...)` is fine, and so is a helper whose name matches one of the
patterns even when its body only mutates.

## When it stays quiet

- The test, or a helper it calls, calls a function that matches `assertions`.
- The test's annotation names an expected exception (`@Test(expected = X::class)`).
- The function is not a test, or has no body.

## Fixtures

`compiler-tests/testData/diagnostics/testMustAssert.kt`

## Implementation notes

`fir/test/checkers/TestMustAssertChecker.kt`, a `FirNamedFunctionChecker` over test functions.
A visitor over the body follows calls into the test's own helpers (each once) and stops at the
first callee whose full or short name matches an assertion glob.

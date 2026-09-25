// KOTRAIL_CONFIG: rules.test.mustAssert=on
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class Counter {
    var count = 0
    fun increment() { count++ }
    fun reset() { count = 0 }
    fun fail(): Nothing = throw IllegalStateException("boom")
}

class CounterTest {
    private val counter = Counter()

    // Not reported: asserts on the result.
    @Test
    fun `increments the count`() {
        counter.increment()
        assertEquals(1, counter.count)
    }

    // Reported: passes as long as nothing throws.
    @Test
    fun <!KOTRAIL_TEST_WITHOUT_ASSERTION!>`resets without checking`<!>() {
        counter.increment()
        counter.reset()
    }

    // Not reported: the assertion sits in a private helper of the test.
    @Test
    fun `increments through a helper`() {
        counter.increment()
        checkCount(1)
    }

    // Not reported: a helper whose name matches *.verify* counts as an assertion.
    @Test
    fun `verifies through a named helper`() {
        counter.increment()
        verifyState()
    }

    // Not reported: an expected failure is an assertion.
    @Test
    fun `fails loudly`() {
        assertThrows(IllegalStateException::class.java) { counter.fail() }
    }

    // Not reported: not a test.
    fun warmUp() {
        counter.increment()
    }

    private fun checkCount(expected: Int) {
        assertEquals(expected, counter.count)
    }

    private fun verifyState() {
        counter.reset()
    }
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, classReference, flexibleType, functionDeclaration,
incrementDecrementExpression, integerLiteral, javaFunction, lambdaLiteral, propertyDeclaration, samConversion,
stringLiteral */

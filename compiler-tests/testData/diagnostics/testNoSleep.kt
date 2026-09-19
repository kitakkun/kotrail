// KOTRAIL_CONFIG: rules.test.noSleep=on
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TickerTest {
    // Reported: a fixed wait on real time.
    @Test
    fun `emits after a tick`() {
        Thread.<!KOTRAIL_TEST_REAL_TIME_WAIT!>sleep<!>(500)
    }

    // Reported: delay outside virtual time is a real wait too.
    @Test
    fun `emits after a tick with delay`() = runBlocking {
        <!KOTRAIL_TEST_REAL_TIME_WAIT!>delay<!>(500)
    }

    // Not reported: inside runTest, delay skips virtual time and returns at once.
    @Test
    fun `emits after a tick on virtual time`() = runTest {
        delay(500)
    }

    // Reported: Thread.sleep still waits real time, even inside runTest.
    @Test
    fun `sleeps on virtual time`() = runTest {
        Thread.<!KOTRAIL_TEST_REAL_TIME_WAIT!>sleep<!>(500)
    }

    // Reported: a wait hidden in a helper lambda of the test.
    @Test
    fun `waits inside a helper`() {
        repeat(3) {
            Thread.<!KOTRAIL_TEST_REAL_TIME_WAIT!>sleep<!>(10)
        }
    }

    // Reported: the wait moved into a helper of this test class; the call in the test is reported.
    @Test
    fun `waits through a member helper`() {
        <!KOTRAIL_TEST_REAL_TIME_WAIT!>awaitSize<!>(3)
    }

    // Reported: a private top-level helper counts too, and delay in it is real time here.
    @Test
    fun `waits through a private helper`() = runBlocking {
        <!KOTRAIL_TEST_REAL_TIME_WAIT!>awaitUntil<!> { true }
    }

    // Not reported: the same helper under runTest runs its delay on virtual time.
    @Test
    fun `waits through a private helper on virtual time`() = runTest {
        awaitUntil { true }
    }

    // Not reported: no test annotation.
    fun warmUp() {
        Thread.sleep(500)
    }

    fun awaitSize(n: Int) {
        while (n > 0) {
            Thread.sleep(10)
        }
    }
}

private suspend fun awaitUntil(condition: () -> Boolean) {
    while (!condition()) {
        delay(10)
    }
}

// Not reported: not a test.
suspend fun pace() {
    delay(100)
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, integerLiteral, javaFunction, lambdaLiteral, suspend */

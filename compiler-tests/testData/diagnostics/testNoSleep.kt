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

    // Not reported: no test annotation.
    fun warmUp() {
        Thread.sleep(500)
    }
}

// Not reported: not a test.
suspend fun pace() {
    delay(100)
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, integerLiteral, javaFunction, lambdaLiteral, suspend */

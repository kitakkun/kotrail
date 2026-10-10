// KOTRAIL_CONFIG: rules.preferDuration=on
// RENDER_DIAGNOSTICS_FULL_TEXT
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test

val PollInterval = 500.milliseconds

// A project function that offers both forms.
fun backoff(millis: Long) {}
fun backoff(interval: Duration) {}

// Both forms, and the number's unit is in its parameter's name.
fun pause(timeoutSeconds: Long) {}
fun pause(timeout: Duration) {}

// Only a number: there is no Duration form to call instead.
fun poll(interval: Long) {}

// The other parameter differs, so the Duration form is not the same call with a unit.
fun retry(millis: Long, times: Int) {}
fun retry(interval: Duration, times: Long) {}

// The Duration form takes a receiver this one does not.
fun settle(millis: Long) {}
fun String.settle(interval: Duration) {}

// A hidden overload cannot be called.
fun hiddenWait(millis: Long) {}
@Deprecated("Use the other one", level = DeprecationLevel.HIDDEN)
fun hiddenWait(interval: Duration) {}

class Ticker {
    fun every(millis: Long) {}
    fun every(period: Duration) {}
}

class Poller {
    fun every(millis: Long) {}
    private fun every(period: Duration) {}
}

class ClientBuilder {
    fun callTimeout(timeout: Long, unit: TimeUnit): ClientBuilder = this
}

// Reported: a bare number handed to a function that also takes a Duration.
@OptIn(FlowPreview::class)
suspend fun reported(flow: Flow<Int>, ticker: Ticker) {
    delay(<!KOTRAIL_PREFER_DURATION!>500<!>)
    delay(<!KOTRAIL_PREFER_DURATION!>1500L<!>)
    withTimeout(<!KOTRAIL_PREFER_DURATION!>5_000<!>) { }
    withTimeoutOrNull(<!KOTRAIL_PREFER_DURATION!>5_000<!>) { }
    flow.debounce(<!KOTRAIL_PREFER_DURATION!>300<!>)
    flow.sample(<!KOTRAIL_PREFER_DURATION!>1_000<!>)
    backoff(<!KOTRAIL_PREFER_DURATION!>250<!>)
    backoff(millis = <!KOTRAIL_PREFER_DURATION!>250<!>)
    ticker.every(<!KOTRAIL_PREFER_DURATION!>16<!>)
    pause(<!KOTRAIL_PREFER_DURATION!>5<!>)
}

// Not reported: the unit is written, the value is named, nothing waits, or there is no Duration form to call.
suspend fun quiet(interval: Long, process: Process, client: ClientBuilder, future: Future<String>, poller: Poller) {
    delay(500.milliseconds)
    delay(PollInterval)
    delay(PollInterval.inWholeMilliseconds)
    delay(interval)
    // Zero and negative values wait nothing; that is another rule's business.
    delay(0)
    delay(-500)
    // No Duration overload.
    poll(250)
    poll(interval = 250)
    Thread.sleep(1500L)
    TimeUnit.SECONDS.sleep(5)
    TimeUnit.SECONDS.toMillis(5)
    // The TimeUnit beside the number states the unit.
    process.waitFor(15, TimeUnit.SECONDS)
    future.get(10, TimeUnit.SECONDS)
    client.callTimeout(10, TimeUnit.SECONDS)
    // The Duration form differs in another parameter or the receiver, is private, or is hidden.
    retry(250, 3)
    settle(250)
    poller.every(16)
    hiddenWait(250)
}

class BackoffTest {
    // Not reported: a test function.
    @Test
    fun `waits between attempts`() {
        backoff(250)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, flexibleType, funWithExtensionReceiver, functionDeclaration,
integerLiteral, javaFunction, javaProperty, lambdaLiteral, nullableType, propertyDeclaration, stringLiteral, suspend,
thisExpression */

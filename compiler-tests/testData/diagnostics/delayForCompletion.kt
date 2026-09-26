// KOTRAIL_CONFIG: rules.delayForCompletion=on
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread

class Socket {
    fun reopen() {}
}

class Manager(private val scope: CoroutineScope, private val socket: Socket) {
    // Starts work and drops the Job: a caller has nothing to wait for.
    fun reconnect() {
        scope.launch { socket.reopen() }
    }

    // Calls a function that starts work: the same, one level up.
    fun restart() {
        reconnect()
    }

    // Hands the caller the Job.
    fun startAndWait(): Job = scope.launch { socket.reopen() }

    suspend fun refresh() {
        socket.reopen()
    }

    fun ready(): Boolean = true

    fun send() {}
}

class Client(private val manager: Manager) {
    // Reported: a fixed wait for what reconnect started.
    suspend fun switch() {
        manager.reconnect()
        <!KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK!>delay(500)<!>
        manager.send()
    }

    // Reported: the starter is two calls away; the message names restart.
    suspend fun switchAgain() {
        manager.restart()
        <!KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK!>delay(500)<!>
    }

    // Reported: a wait nested in an if, after the start.
    suspend fun switchIfNeeded(needed: Boolean) {
        manager.reconnect()
        if (needed) <!KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK!>delay(1_000L)<!>
    }

    // Not reported: the amount is not fixed.
    suspend fun switchWithin(timeout: Long) {
        manager.reconnect()
        delay(timeout)
    }

    // Not reported: refresh suspends, so the caller already waited for it.
    suspend fun refreshThenPause() {
        manager.refresh()
        delay(500)
    }

    // Not reported: nothing was started before the wait.
    suspend fun pause() {
        delay(500)
        manager.send()
    }

    // Not reported: polling in a loop has a reason to wait.
    suspend fun poll() {
        manager.reconnect()
        while (!manager.ready()) delay(50)
    }

    // Not reported: startAndWait hands over the Job; the wait is about something else.
    suspend fun startThenPause() {
        manager.startAndWait()
        delay(500)
    }

    // Not reported: a suspending call between the start and the wait.
    suspend fun startRefreshPause() {
        manager.reconnect()
        manager.refresh()
        delay(500)
    }
}

// Reported: a thread started and dropped, then a fixed sleep, in a blocking function.
fun warmUp() {
    thread { Socket().reopen() }
    <!KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK!>Thread.sleep(100)<!>
}

class ManagerTest {
    // Not reported: tests are test.noSleep's business.
    @Test
    fun `reconnects`() {
        thread { Socket().reopen() }
        Thread.sleep(100)
    }
}

// Not reported: a debounce. The delay opens the lambda it is in; the cancel and the launch before
// it are statements of another block, and nothing the delay waits for was started in its own.
class Search(private val scope: CoroutineScope) {
    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(300)
            println(query)
        }
    }
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, functionDeclaration, ifExpression, javaFunction, lambdaLiteral,
nullableType, primaryConstructor, propertyDeclaration, safeCall, suspend, whileLoop */

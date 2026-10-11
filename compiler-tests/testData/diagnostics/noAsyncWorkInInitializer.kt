// KOTRAIL_CONFIG: rules.noAsyncWorkInInitializer=on
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.concurrent.Executor
import kotlin.concurrent.schedule
import kotlin.concurrent.thread
import kotlin.properties.Delegates

class Request

fun handle(request: Request) {}

fun log(message: String) {}

class Captures(private val scope: CoroutineScope, private val executor: Executor, private val events: Flow<Int>) {
    // Reported: a consumer loop starts while the object is built, hidden in a Channel's initializer.
    private val requests = Channel<Request>(Channel.UNLIMITED).also { requests ->
        <!KOTRAIL_ASYNC_WORK_IN_INITIALIZER!>scope.launch(Dispatchers.IO) {
            for (request in requests) handle(request)
        }<!>
    }

    // Reported: a thread, through the same inline lambda.
    private val frames = Channel<Int>().also { frames ->
        <!KOTRAIL_ASYNC_WORK_IN_INITIALIZER!>thread { frames.close() }<!>
    }

    // Reported with the lazy message: lazy defers the start to the first read, whichever code makes it.
    private val updates by lazy {
        Channel<Int>().also { updates -> <!KOTRAIL_ASYNC_WORK_IN_LAZY!>scope.launch { updates.send(1) }<!> }
    }

    // Reported with the lazy message: also when lazy is given a mode.
    private val drained by lazy(LazyThreadSafetyMode.NONE) {
        ArrayDeque<Int>().also { <!KOTRAIL_ASYNC_WORK_IN_LAZY!>thread { log("drain") }<!> }
    }

    // Reported with the initializer message: any other delegate is built with the object.
    private var latest by Delegates.observable(Channel<Int>().also { latest -> <!KOTRAIL_ASYNC_WORK_IN_INITIALIZER!>scope.launch { latest.send(0) }<!> }) { _, _, _ -> }

    // Reported: through any depth of inline lambdas.
    private val workers = mutableListOf<Int>().apply {
        listOf(1, 2).forEach { index -> <!KOTRAIL_ASYNC_WORK_IN_INITIALIZER!>scope.launch { add(index) }<!> }
    }

    // Reported: a task posted to an executor.
    private val queue = ArrayDeque<String>().apply {
        <!KOTRAIL_ASYNC_WORK_IN_INITIALIZER!>executor.execute { log("drained") }<!>
    }

    // Reported: a timer task, through the Kotlin extension.
    private val ticker = Timer().also { timer -> <!KOTRAIL_ASYNC_WORK_IN_INITIALIZER!>timer.schedule(1_000L) { log("tick") }<!> }

    // Not reported: a lazy start, given by position, leaves the work to whoever starts or awaits it.
    private val renders: Map<String, Deferred<Int>> = listOf("intro", "loop").associateWith { track ->
        scope.async(Dispatchers.IO, CoroutineStart.LAZY) { track.length }
    }

    // Not reported: the same given by name.
    private val warmUp = Channel<Int>().also { warmUp -> scope.launch(start = CoroutineStart.LAZY) { warmUp.send(0) } }

    // Reported: any other start begins the work at once.
    private val eager: Map<String, Deferred<Int>> = listOf("intro").associateWith { track ->
        <!KOTRAIL_ASYNC_WORK_IN_INITIALIZER!>scope.async(Dispatchers.IO, CoroutineStart.UNDISPATCHED) { track.length }<!>
    }

    // Reported: a start that is not written as the constant counts as a start, whatever it holds.
    private val chosen = Channel<Int>().also { chosen -> <!KOTRAIL_ASYNC_WORK_IN_INITIALIZER!>scope.launch(start = lazyStart) { chosen.send(0) }<!> }

    // Not reported: a thread created without being started.
    private val idle = ArrayDeque<Int>().also { thread(start = false) { log("idle") } }

    // Not reported: the Job is the value, and its type says work begins.
    val job = scope.launch { log("refresh") }

    // Not reported: the same through launchIn.
    val collection = events.onEach { value -> log("$value") }.launchIn(scope)

    // Not reported: the Job is the lazy value.
    val lazyJob by lazy { scope.launch { log("refresh") } }

    // Not reported: also returns its receiver, so the Job is still the value.
    val loggedJob = scope.launch { log("refresh") }.also { log("started") }

    // Not reported: the lambda runs when it is invoked, not while the object is built.
    val onClick = { scope.launch { log("click") } }

    // Not reported: map's transform is crossinline and runs when the flow is collected.
    val doubled = events.map { value ->
        scope.launch { log("$value") }
        value * 2
    }

    // Not reported: a plain side effect, not a start of work.
    private val pending = Channel<Request>().also { log("built") }

    // Not reported: init is visible construction code.
    init {
        scope.launch { consume(requests) }
    }

    fun start() {
        // Not reported: a local runs when the function runs.
        val local = Channel<Int>().also { local -> scope.launch { local.send(1) } }
        local.close()
    }

    private suspend fun consume(requests: ReceiveChannel<Request>) {
        for (request in requests) handle(request)
    }

    companion object {
        private val sharedScope = CoroutineScope(Dispatchers.Default)

        // Reported: the companion is built when the class is first used.
        private val shared = Channel<Int>().also { shared -> <!KOTRAIL_ASYNC_WORK_IN_INITIALIZER!>sharedScope.launch { shared.send(0) }<!> }
    }
}

val appScope = CoroutineScope(Dispatchers.Default)

val lazyStart = CoroutineStart.LAZY

// Reported: a top-level property starts work when its file is first used.
val ticks = Channel<Int>().also { ticks -> <!KOTRAIL_ASYNC_WORK_IN_INITIALIZER!>appScope.launch { ticks.send(0) }<!> }

// Not reported: the Thread is the value.
val worker = thread { log("work") }

val optionalScope: CoroutineScope? = null

// Not reported: the Job is the value of a safe call.
val maybeJob: Job? = optionalScope?.launch { log("work") }

/* GENERATED_FIR_TAGS: classDeclaration, companionObject, forLoop, functionDeclaration, init, integerLiteral,
javaFunction, lambdaLiteral, localProperty, multiplicativeExpression, nullableType, objectDeclaration,
primaryConstructor, propertyDeclaration, propertyDelegate, safeCall, samConversion, stringLiteral, suspend */

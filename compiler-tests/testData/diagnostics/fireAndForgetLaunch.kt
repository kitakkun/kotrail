// KOTRAIL_CONFIG: rules.fireAndForgetLaunch=on
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class Socket {
    fun reopen() {}
}

interface Restartable {
    fun restart()
}

class Manager(private val scope: CoroutineScope, private val socket: Socket) : Restartable {
    // Reported: the Job is dropped, so a caller can only guess when the work is done.
    fun <!KOTRAIL_FIRE_AND_FORGET_LAUNCH!>reconnect<!>() {
        scope.launch { socket.reopen() }
    }

    // Reported once, on the outer launch: the inner one is part of the work it started.
    fun <!KOTRAIL_FIRE_AND_FORGET_LAUNCH!>reconnectTwice<!>() {
        scope.launch {
            launch { socket.reopen() }
        }
    }

    // Not reported: the caller gets the Job.
    fun start(): Job = scope.launch { socket.reopen() }

    // Not reported: the caller awaits it.
    suspend fun refresh() {
        socket.reopen()
    }

    // Not reported: an event handler that launches and returns is the ordinary shape.
    fun onClick() {
        scope.launch { socket.reopen() }
    }

    // Not reported: private; its callers are in reach.
    private fun warm() {
        scope.launch { socket.reopen() }
    }

    // Not reported: an override follows its interface.
    override fun restart() {
        scope.launch { socket.reopen() }
    }

    // Not reported: the starter's result is kept.
    fun track() {
        val job = scope.launch { socket.reopen() }
        println(job)
    }

    fun use() {
        warm()
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, interfaceDeclaration, lambdaLiteral, localProperty,
override, primaryConstructor, propertyDeclaration, suspend */

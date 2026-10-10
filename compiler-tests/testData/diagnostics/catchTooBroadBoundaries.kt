// KOTRAIL_CONFIG: rules.catchTooBroad=on, rules.catchTooBroad.loggers=log,Log.*, rules.unguardedBoundary.annotations=FailureBoundary
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import java.io.IOException
import java.io.OutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

object Log {
    fun warn(message: String, e: Throwable) {}
}

fun log(e: Throwable) {}
fun handle(value: Int) {}

annotation class FailureBoundary

// Not reported: the project declares this function a boundary through unguardedBoundary.annotations,
// which catchTooBroad reads while unguardedBoundary itself is off.
@FailureBoundary
fun dispatch() {
    try {
        handle(1)
    } catch (e: Exception) {
        log(e)
    }
}

class Sender(private val scope: CoroutineScope, private val out: OutputStream) {
    // Not reported: the loop of the launched coroutine is a failure boundary, and the failure is logged there.
    val requests = Channel<Int>(Channel.UNLIMITED).also {
        scope.launch {
            for (request in it) {
                try {
                    out.write(request)
                } catch (e: IOException) {
                    Log.warn("write failed", e)
                } catch (e: Exception) {
                    Log.warn("request failed", e)
                }
            }
        }
    }
}

class SilentSender(private val scope: CoroutineScope, private val out: OutputStream) {
    // Reported: at the boundary, but the exception is never read, so the failure vanishes.
    val requests = Channel<Int>(Channel.UNLIMITED).also {
        scope.launch {
            for (request in it) {
                try {
                    out.write(request)
                } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Exception<!>) {
                }
            }
        }
    }
}

class Device(scope: CoroutineScope) {
    init {
        // Not reported: the launched coroutine is the boundary, wherever the launch sits.
        scope.launch {
            try {
                open()
            } catch (e: Exception) {
                Log.warn("open failed", e)
            }
        }

        // Reported: an init block is not a boundary.
        try {
            open()
        } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Exception<!>) {
            Log.warn("open failed", e)
        }
    }

    fun open() {}
}

class Starter(private val device: Device) {
    // Reported: apply runs in place, and start is not a boundary.
    fun start() = device.apply {
        try {
            open()
        } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Exception<!>) {
            log(e)
        }
    }

    // Reported: toString is called by the project's own code, so overriding it makes no boundary.
    override fun toString(): String = try {
        device.toString()
    } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Exception<!>) {
        log(e)
        "device"
    }
}

class Clicks(private val state: StringBuilder) : View.OnClickListener {
    // Not reported: an override of a library member is a boundary, through apply.
    override fun onClick(v: View) {
        state.apply {
            try {
                v.performClick()
            } catch (e: Exception) {
                log(e)
            }
        }
    }
}

class PluginLoader : ClassLoader() {
    // Reported: loadClass returns a value, so it is no boundary: its caller receives the failure.
    override fun loadClass(name: String): Class<*> {
        try {
            return super.loadClass(name)
        } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Exception<!>) {
            log(e)
            return Any::class.java
        }
    }
}

class Worker : Thread() {
    // Not reported: Thread.run is a library member.
    override fun run() {
        while (!isInterrupted) {
            if (isAlive) {
                try {
                    handle(1)
                } catch (e: Exception) {
                    log(e)
                }
            }
        }
    }
}

fun bind(view: View) {
    // Not reported: a lambda converted to a library interface is a boundary.
    view.setOnClickListener {
        try {
            it.performClick()
        } catch (e: Exception) {
            log(e)
        }
    }

    // Not reported: so is a SAM constructor of one.
    val task = Runnable {
        try {
            handle(1)
        } catch (e: Exception) {
            log(e)
        }
    }
    thread {
        task.run()
        // Not reported: thread's lambda is a boundary.
        try {
            handle(2)
        } catch (e: Exception) {
            log(e)
        }
    }
}

class Events(private val scope: CoroutineScope, private val events: Flow<Int>) {
    // Not reported: launchIn makes its receiver chain a boundary.
    fun start() {
        events.onEach {
            try {
                handle(it)
            } catch (e: Exception) {
                log(e)
            }
        }.launchIn(scope)
    }

    // Reported: the same chain, collected elsewhere.
    fun handled(): Flow<Int> = events.onEach {
        try {
            handle(it)
        } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Exception<!>) {
            log(e)
        }
    }
}

@Composable
fun Screen(key: Int) {
    // Not reported: an effect's lambda is a boundary.
    LaunchedEffect(key) {
        try {
            handle(key)
        } catch (e: Exception) {
            log(e)
        }
    }
}

fun load(scope: CoroutineScope) {
    // Reported: async hands its failure to whoever awaits it.
    scope.async {
        try {
            handle(1)
        } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Exception<!>) {
            log(e)
        }
    }

    scope.launch {
        // Reported: withContext returns its failure to the coroutine that called it.
        withContext(Dispatchers.IO) {
            try {
                handle(1)
            } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Exception<!>) {
                log(e)
            }
        }

        // Reported: a nested function is not the launched body.
        fun step() {
            try {
                handle(2)
            } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Exception<!>) {
                log(e)
            }
        }
        step()

        // Reported: runCatching already catches everything around this try.
        runCatching {
            try {
                handle(3)
            } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Exception<!>) {
                log(e)
            }
        }

        // Not reported: a try nested in a try at the boundary is still at the boundary.
        try {
            try {
                handle(4)
            } catch (e: Exception) {
                log(e)
            }
        } finally {
            handle(5)
        }
    }
}

/* GENERATED_FIR_TAGS: annotationDeclaration, classDeclaration, forLoop, functionDeclaration, ifExpression, init,
integerLiteral, javaFunction, javaProperty, lambdaLiteral, localFunction, localProperty, objectDeclaration, override,
primaryConstructor, propertyDeclaration, samConversion, stringLiteral, tryExpression, whileLoop */

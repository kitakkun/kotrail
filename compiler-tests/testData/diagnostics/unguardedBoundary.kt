// KOTRAIL_CONFIG: rules.unguardedBoundary=on, rules.unguardedBoundary.names=Dispatcher.dispatch, rules.unguardedBoundary.annotations=FailureBoundary, rules.unguardedBoundary.guardedScopes=HostPlugin.pluginScope,*.safeScope, rules.catchTooBroad=on, rules.catchTooBroad.loggers=Log.*,log
// RENDER_DIAGNOSTICS_FULL_TEXT
import android.view.View
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.function.Consumer
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image

annotation class FailureBoundary

object Log {
    fun warn(message: String, e: Throwable) {}
}

fun log(e: Throwable) {}
fun handle(value: Int) {}

class Sender(private val scope: CoroutineScope, private val out: OutputStream) {
    // Reported: any failure but an IOException ends the consumer loop, and the channel fills up unread.
    val requests = Channel<Int>(Channel.UNLIMITED).also {
        scope.launch {
            for (request in it) {
                try {
                    out.write(request)
                } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IOException<!>) {
                    Log.warn("write failed", e)
                }
            }
        }
    }
}

class GuardedSender(private val scope: CoroutineScope, private val out: OutputStream) {
    // Not reported, by either rule: the broad clause logs what the narrow one does not name.
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
    // Reported by catchTooBroad alone: the broad clause guards the loop, but never reads what it caught.
    val requests = Channel<Int>(Channel.UNLIMITED).also {
        scope.launch {
            for (request in it) {
                try {
                    out.write(request)
                } catch (e: IOException) {
                    Log.warn("write failed", e)
                } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Exception<!>) {
                }
            }
        }
    }
}

fun start(scope: CoroutineScope, out: OutputStream) {
    val handler = CoroutineExceptionHandler { _, e -> log(e) }

    // Not reported: the scope is handed a CoroutineExceptionHandler.
    scope.launch(handler) {
        try {
            out.write(1)
        } catch (e: IOException) {
        }
    }

    // Not reported: so it is through a context sum.
    scope.launch(Dispatchers.IO + handler) {
        try {
            out.write(1)
        } catch (e: IOException) {
        }
    }

    // Not reported: the handler is part of the scope built in the same expression.
    CoroutineScope(SupervisorJob() + handler).launch {
        try {
            out.write(1)
        } catch (e: IOException) {
        }
    }

    // Reported: a thread has no handler to hand anything to.
    thread {
        try {
            out.write(1)
        } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IOException<!>) {
        }
    }

    scope.launch {
        // Not reported: a broad clause around it at the same root catches the rest.
        try {
            try {
                out.write(1)
            } catch (e: IOException) {
                Log.warn("write failed", e)
            }
        } catch (e: Exception) {
            Log.warn("send failed", e)
        }

        // Not reported: only the project's own code is called.
        try {
            handle(1)
        } catch (e: IllegalStateException) {
            Log.warn("handle failed", e)
        }

        // Not reported: only the standard library is called.
        try {
            handle("1".toInt())
        } catch (e: NumberFormatException) {
            Log.warn("parse failed", e)
        }

        // Not reported: every clause rethrows, so the try only translates.
        try {
            out.write(1)
        } catch (e: IOException) {
            throw IllegalStateException("write failed", e)
        }
    }
}

fun notABoundary(out: OutputStream) {
    // Not reported: the caller sees whatever this does not catch.
    try {
        out.write(1)
    } catch (e: IOException) {
        Log.warn("write failed", e)
    }
}

class Values(private val scope: CoroutineScope, private val out: OutputStream, private val events: Flow<Int>) {
    // Reported: stateIn makes the chain a boundary, and nothing downstream catches.
    val written: StateFlow<Int> = events.map {
        try {
            out.write(it)
            it
        } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IOException<!>) {
            -1
        }
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    // Not reported: a catch operator downstream of the lambda handles the rest.
    val caught: StateFlow<Int> = events.map {
        try {
            out.write(it)
            it
        } catch (e: IOException) {
            -1
        }
    }.catch { log(it) }.stateIn(scope, SharingStarted.Eagerly, 0)
}

class Submit(private val out: OutputStream) : View.OnClickListener {
    // Reported: a library listener is a boundary.
    override fun onClick(v: View) {
        try {
            out.write(1)
        } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IOException<!>) {
            Log.warn("submit failed", e)
        }
    }
}

fun bind(view: View) {
    // Reported: so is a lambda converted to one.
    view.setOnClickListener {
        try {
            it.performClick()
        } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IllegalStateException<!>) {
            Log.warn("click failed", e)
        }
    }
}

class Dispatcher(private val out: OutputStream) {
    // Reported: named a boundary in the configuration.
    fun dispatch() {
        try {
            out.write(1)
        } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IOException<!>) {
            Log.warn("dispatch failed", e)
        }
    }
}

@FailureBoundary
class Jobs(private val out: OutputStream) {
    // Reported: every member of an annotated class is a boundary.
    fun flush() {
        try {
            out.flush()
        } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IOException<!>) {
            Log.warn("flush failed", e)
        }
    }
}

class Event(val code: Int)

class EventWriter(private val out: OutputStream) : Consumer<Event> {
    // Reported: accept returns nothing, so the library calls it and forgets what escapes it.
    override fun accept(event: Event) {
        try {
            out.write(event.code)
        } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IOException<!>) {
            Log.warn("write failed", e)
        }
    }
}

class PluginLoader : ClassLoader() {
    // Not reported: loadClass returns a value, so its caller receives whatever this does not catch.
    override fun loadClass(name: String): Class<*> {
        try {
            return super.loadClass(name)
        } catch (e: ClassNotFoundException) {
            return findSystemClass(name)
        }
    }
}

// Not reported: neither is a converted lambda that returns a value, for whoever calls it.
fun writeTask(out: OutputStream): Callable<Boolean> = Callable {
    try {
        out.write(1)
        true
    } catch (e: IOException) {
        false
    }
}

abstract class HostPlugin {
    // Stands for a scope the host builds with a CoroutineExceptionHandler; guardedScopes names it.
    val pluginScope: CoroutineScope = CoroutineScope(SupervisorJob())
}

object Scopes {
    fun safeScope(): CoroutineScope = CoroutineScope(SupervisorJob())
}

class Plugin(private val out: OutputStream, private val events: Flow<Int>) : HostPlugin() {
    fun start() {
        // Not reported: the configured scope carries a handler.
        pluginScope.launch {
            try {
                out.write(1)
            } catch (e: IOException) {
                Log.warn("write failed", e)
            }
        }

        // Not reported: so does a local val that holds it.
        val scope = pluginScope
        scope.launch {
            try {
                out.write(1)
            } catch (e: IOException) {
                Log.warn("write failed", e)
            }
        }

        // Not reported: so does the scope a configured function returns.
        Scopes.safeScope().launch {
            try {
                out.write(1)
            } catch (e: IOException) {
                Log.warn("write failed", e)
            }
        }

        // Not reported: the configured scope handed to launchIn.
        events.onEach {
            try {
                out.write(it)
            } catch (e: IOException) {
                Log.warn("write failed", e)
            }
        }.launchIn(pluginScope)
    }
}

class Decoder(private val scope: CoroutineScope) {
    fun decode(png: ByteArray, capture: (Int) -> Unit, path: String) {
        // Reported, naming Image.makeFromEncoded: the call that decodes comes first, close after it.
        scope.launch {
            try {
                Image.makeFromEncoded(png).close()
            } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IllegalArgumentException<!>) {
                Log.warn("decode failed", e)
            }
        }

        // Reported, naming capture(...): a function-typed parameter runs code nobody here knows.
        scope.launch {
            try {
                capture(png.size)
            } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IllegalStateException<!>) {
                Log.warn("capture failed", e)
            }
        }

        // Reported, naming the constructor: nothing else is called.
        scope.launch {
            try {
                FileInputStream(path)
            } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: FileNotFoundException<!>) {
                Log.warn("open failed", e)
            }
        }
    }
}

fun writeAndRecord(command: Any) {}

class Recorder(private val scope: CoroutineScope, private val capture: suspend (Any) -> Unit) {
    fun record(label: String, options: StringBuilder?) {
        // Reported, naming capture(...): the call around the constructor in its argument comes first.
        scope.launch {
            try {
                capture(options ?: StringBuilder())
            } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IllegalStateException<!>) {
                Log.warn("capture failed", e)
            }
        }

        // Reported, naming System.setProperty: an outer call out before the call out in its argument.
        scope.launch {
            try {
                System.setProperty(label, System.getenv(label))
            } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IllegalStateException<!>) {
                Log.warn("record failed", e)
            }
        }

        // Reported, naming the constructor: the module's own function around it reaches no call out.
        scope.launch {
            try {
                writeAndRecord(StringBuilder(label))
            } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IllegalStateException<!>) {
                Log.warn("record failed", e)
            }
        }
    }
}

class Attribute(val name: String)

class Inspector(private val scope: CoroutineScope, private val out: OutputStream) {
    fun update(name: String) {
        // Reported, naming the module's own function the try calls and the call out it reaches.
        scope.launch {
            try {
                sendAndRecord(Attribute(name))
            } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IllegalStateException<!>) {
                Log.warn("update failed", e)
            }
        }

        // Reported: the call out is followed through three of the module's own functions.
        scope.launch {
            try {
                relay(name)
            } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IllegalStateException<!>) {
                Log.warn("relay failed", e)
            }
        }

        // Not reported: the module's own functions do local work alone, recursion included.
        scope.launch {
            try {
                handle(measure(name))
                countDown(3)
            } catch (e: IllegalStateException) {
                Log.warn("measure failed", e)
            }
        }

        // Not reported: a call out four of the module's own functions deep is not followed.
        scope.launch {
            try {
                forward(name)
            } catch (e: IllegalStateException) {
                Log.warn("forward failed", e)
            }
        }
    }

    private fun sendAndRecord(attribute: Attribute) {
        val size = encode(attribute)
        out.write(size)
    }

    private fun encode(attribute: Attribute): Int = attribute.name.length

    private fun relay(name: String) {
        stage(name)
    }

    private fun stage(name: String) {
        sendAndRecord(Attribute(name))
    }

    private fun measure(name: String): Int = name.length * 2

    private fun countDown(n: Int) {
        if (n > 0) countDown(n - 1)
    }

    private fun forward(name: String) {
        hop(name)
    }

    private fun hop(name: String) {
        relay(name)
    }
}

/* GENERATED_FIR_TAGS: additiveExpression, annotationDeclaration, classDeclaration, forLoop, functionDeclaration,
integerLiteral, javaFunction, lambdaLiteral, localProperty, objectDeclaration, override, primaryConstructor,
propertyDeclaration, samConversion, stringLiteral, tryExpression */

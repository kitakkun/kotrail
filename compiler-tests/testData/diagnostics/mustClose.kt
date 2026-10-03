// KOTRAIL_CONFIG: rules.mustClose=on
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI
import kotlin.concurrent.thread

class Pooled : AutoCloseable {
    override fun close() {}
}

class Registry {
    var current: BufferedReader? = null
    fun adopt(reader: BufferedReader) {}
}

fun consume(stream: InputStream) {}

fun leaks(path: String, registry: Registry): String {
    // Reported: created, read, and never closed.
    val reader = <!KOTRAIL_RESOURCE_NOT_CLOSED!>File(path).bufferedReader()<!>
    val first = reader.readLine()

    // Reported: a constructor of an AutoCloseable, dropped after one call.
    val second = <!KOTRAIL_RESOURCE_NOT_CLOSED!>BufferedReader(StringReader("x"))<!>.readLine()

    return first + second
}

fun closes(path: String, registry: Registry, pooled: () -> Pooled): String {
    // Not reported: use, directly, through a scope function, and through a safe call.
    val a = File(path).bufferedReader().use { it.readLine() }
    val b = File(path).bufferedReader().apply { mark(1) }.use { it.readLine() }
    val c = File(path).takeIf { it.exists() }?.bufferedReader()?.use { it.readLine() }

    // Not reported: a local closed later, in a finally or plainly, or with use.
    val d = File(path).bufferedReader()
    try {
        d.readLine()
    } finally {
        d.close()
    }
    val e = File(path).bufferedReader()
    e.readLine()
    e.close()
    val f = File(path).inputStream()
    f.use { }

    // Not reported: handed on, as a return value, a property, or an argument; whoever receives it owns it.
    registry.current = File(path).bufferedReader()
    registry.adopt(File(path).bufferedReader())
    val g = File(path).inputStream()
    consume(g)

    // Not reported: not a creation. A function that returns an existing resource is not on the list.
    val h = pooled()
    h.toString()
    return a + b + c + d + e + g + h
}

fun opens(path: String): BufferedReader = File(path).bufferedReader()

class Probe {
    private var installation: Installation? = null
    private val lock = Any()

    class Installation : AutoCloseable {
        override fun close() {}
    }

    // Not reported: created once, stored in a property and returned; the owner closes it.
    fun install(): AutoCloseable = synchronized(lock) { installation ?: Installation().also { installation = it } }
}

fun consumes(path: String, process: Process): String {
    // Not reported: consuming helpers close the reader; a wrapper's closing closes what it wraps.
    val lines = File(path).bufferedReader().useLines { it.count() }
    val all = process.inputStream.bufferedReader().readLines()
    val raw = FileInputStream(path)
    raw.bufferedReader().use { it.readLine() }

    // Not reported: an in-memory buffer holds nothing a close would free.
    val bytes = ByteArrayOutputStream().also { it.write(1) }.toByteArray()

    // Reported: readText leaves the reader, and the file behind it, open.
    val text = <!KOTRAIL_RESOURCE_NOT_CLOSED!>File(path).bufferedReader()<!>.readText()
    return "$lines $all $text ${bytes.size}"
}

fun runs(command: String): String {
    // Not reported: a process owns its pipes, and closes them as it ends.
    val process = ProcessBuilder(command).start()
    val out = process.inputStream.bufferedReader().readText()
    val err = process.errorStream.bufferedReader().readText()
    process.outputStream.bufferedWriter().write("q")
    val held = process.inputStream
    val again = held.bufferedReader().readText()
    val exec = Runtime.getRuntime().exec(arrayOf(command)).inputStream.bufferedReader().readText()
    return out + err + again + exec
}

fun fetch(url: String): String {
    // Not reported: the connection owns its streams, and the body disconnects it.
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        if (connection.responseCode >= 400) return connection.errorStream.bufferedReader().readText()
        val body = connection.inputStream.bufferedReader().readText()
        val input = connection.inputStream
        val again = input.bufferedReader().readText()
        return body + again
    } finally {
        connection.disconnect()
    }
}

fun post(connection: HttpURLConnection, payload: String) {
    // Not reported: disconnected later on the path.
    connection.outputStream.bufferedWriter().write(payload)
    connection.disconnect()
}

fun fetchKept(url: String): String {
    // Reported: nothing disconnects the connection, so the reader is this function's to close.
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    return <!KOTRAIL_RESOURCE_NOT_CLOSED!>connection.inputStream.bufferedReader()<!>.readText()
}

fun openNarrowed(path: String): BufferedReader = File(path).takeIf { it.exists() }?.bufferedReader() ?: error("missing")

class Surface : AutoCloseable {
    override fun close() {}
}

fun onDispose(block: () -> Unit) {}

fun handsOffClosing() {
    // Not reported: a bound reference hands the closing to whoever runs it.
    val surface = Surface()
    thread(block = surface::close)
    val other = Surface()
    Runtime.getRuntime().addShutdownHook(Thread(other::close))
    val third = Surface()
    onDispose(third::close)
}

class Wrapper(private val inner: InputStream) : InputStream() {
    override fun read(): Int = inner.read()
}

class Bounded(private val parent: Reader, private val limit: Int) : Reader() {
    override fun read(cbuf: CharArray, off: Int, len: Int): Int = parent.read(cbuf, off, minOf(len, limit))
    override fun close() {}
}

class Source(private val stream: InputStream) : Reader() {
    override fun read(cbuf: CharArray, off: Int, len: Int): Int = -1
    override fun close() {}

    // Not reported: a wrapper over `this`, a borrowed stream; whoever owns this reader closes it.
    fun limited(n: Int): Int = Bounded(this, n).read()

    // Not reported: a wrapper over a stream borrowed from a property the class owns.
    fun peek(): Int = Wrapper(this.stream).read()
}

fun borrowedStreams(parameterStream: InputStream, path: String): Int {
    // Not reported: a wrapper over a stream borrowed from the caller; the caller closes it.
    val wrapper = Wrapper(parameterStream)
    wrapper.read()

    // Reported: the wrapped stream is created here, so the wrapper carries the obligation.
    val owned = <!KOTRAIL_RESOURCE_NOT_CLOSED!>Wrapper(FileInputStream(path))<!>
    return owned.read()
}

fun chainedOnBorrowed(parameterStream: InputStream): String {
    // Not reported: a wrapper chained on a wrapper over a borrowed stream, or on a parameter, closes a stream the caller owns.
    val chained = Wrapper(parameterStream).bufferedReader()
    val direct = parameterStream.bufferedReader()
    val nested = BufferedReader(InputStreamReader(parameterStream))
    return chained.readLine() + direct.readLine() + nested.readLine()
}

fun nestedWrappers(parameterStream: InputStream, path: String): String {
    // Not reported: the inner reader wraps a stream borrowed from the caller, so the outer one does too.
    val borrowed = BufferedReader(InputStreamReader(parameterStream))
    val reader = InputStreamReader(parameterStream)
    val throughLocal = BufferedReader(reader)

    // Reported: the innermost stream is created here, so the outer wrapper carries the obligation.
    val owned = <!KOTRAIL_RESOURCE_NOT_CLOSED!>BufferedReader(InputStreamReader(FileInputStream(path)))<!>
    return borrowed.readLine() + throughLocal.readLine() + owned.readLine()
}

class Holder(private val stream: InputStream) {
    // Not reported: a wrapper over a member property; the class owns the stream.
    fun first(): String = this.stream.bufferedReader().readLine()
    fun second(): Int = stream.buffered().read()
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, classDeclaration, elvisExpression, flexibleType,
functionDeclaration, functionalType, integerLiteral, javaFunction, lambdaLiteral, localProperty, nullableType, override,
propertyDeclaration, safeCall, stringLiteral, tryExpression */

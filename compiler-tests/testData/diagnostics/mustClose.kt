// KOTRAIL_CONFIG: rules.mustClose=on
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.StringReader

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

    // Reported: readText leaves the reader, and the pipe behind it, open.
    val text = <!KOTRAIL_RESOURCE_NOT_CLOSED!>process.inputStream.bufferedReader()<!>.readText()
    return "$lines $all $text ${bytes.size}"
}

fun openNarrowed(path: String): BufferedReader = File(path).takeIf { it.exists() }?.bufferedReader() ?: error("missing")

/* GENERATED_FIR_TAGS: additiveExpression, assignment, classDeclaration, elvisExpression, flexibleType,
functionDeclaration, functionalType, integerLiteral, javaFunction, lambdaLiteral, localProperty, nullableType, override,
propertyDeclaration, safeCall, stringLiteral, tryExpression */

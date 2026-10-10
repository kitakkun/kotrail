// KOTRAIL_CONFIG: rules.noOpCall=on
// RENDER_DIAGNOSTICS_FULL_TEXT
import com.kitakkun.kotrail.noop.InferredNoOpWhen
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test

const val NO_TIME = 0L
val EMPTY_PAYLOAD = ByteArray(0)

class Socket {
    fun write(bytes: ByteArray) {}
}

val socket = Socket()

// Does nothing when bytes is empty.
fun send(bytes: ByteArray) {
    if (bytes.isEmpty()) return
    socket.write(bytes)
}

// Does nothing when text is blank.
fun log(text: String) {
    if (text.isBlank()) return
    println(text)
}

// Guards in a row, the second written as a when: any one of them makes the call do nothing.
fun resize(width: Int, height: Int) {
    if (width <= 0) return
    when {
        height <= 0 -> return
        height > 10_000 -> return
    }
    println(width * height)
}

// A guard whose else does the work: the guard's condition still counts.
fun ping(times: Int) {
    if (times == 0) return else println("ping")
    println(times)
}

// A guard on a parameter left to its default.
fun notify(message: String = "") {
    if (message.isEmpty()) return
    println(message)
}

class Sender {
    // A final member: nothing can run in its place.
    fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        socket.write(bytes)
    }
}

// Not a guard: it logs before returning.
fun upload(bytes: ByteArray) {
    if (bytes.isEmpty()) {
        println("nothing to upload")
        return
    }
    socket.write(bytes)
}

// Not a guard: something comes before it.
fun flush(bytes: ByteArray) {
    println("flushing")
    if (bytes.isEmpty()) return
    socket.write(bytes)
}

// Returns a value: the result is the point of the call.
fun count(bytes: ByteArray): Int {
    if (bytes.isEmpty()) return 0
    return bytes.size
}

// Open: an override may do something with an empty array.
open class Channel {
    open fun push(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        socket.write(bytes)
    }
}

class LoggingChannel : Channel() {
    override fun push(bytes: ByteArray) {
        println("push")
        super.push(bytes)
    }
}

// A fact written by hand, as another module's compiled class would carry it.
@InferredNoOpWhen("count <= 0")
fun fromLibrary(count: Int) {
    println(count)
}

// Reported: the arguments make the callee return at once.
suspend fun reported() {
    <!KOTRAIL_CALL_DOES_NOTHING!>send(byteArrayOf())<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>send(ByteArray(0))<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>send(EMPTY_PAYLOAD)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>log("")<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>log("   ")<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>resize(0, 10)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>resize(10, 0)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>resize(10, 20_000)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>ping(0)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>notify()<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>Sender().send(byteArrayOf())<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>fromLibrary(0)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>delay(0)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>delay(-5)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>delay(NO_TIME)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>delay(Duration.ZERO)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>delay(0.milliseconds)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>delay((-1).milliseconds)<!>
    <!KOTRAIL_CALL_DOES_NOTHING!>delay(-1.seconds)<!>
}

// Not reported: an argument is unknown, the condition is false, or the callee has no such guard.
suspend fun quiet(payload: ByteArray, text: String, x: Long, timeout: Duration, channel: Channel) {
    send(payload)
    send(byteArrayOf(1, 2))
    log("hello")
    log(text)
    resize(10, 20)
    resize(10, x.toInt())
    ping(3)
    notify("ready")
    fromLibrary(1)
    upload(byteArrayOf())
    flush(byteArrayOf())
    count(byteArrayOf())
    channel.push(byteArrayOf())
    LoggingChannel().push(byteArrayOf())
    delay(10)
    delay(x)
    delay(10.milliseconds)
    delay(1.seconds)
    delay(timeout)
}

class SenderTest {
    // Not reported: a test function.
    @Test
    fun `sends nothing for an empty payload`() {
        send(byteArrayOf())
        log("")
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, comparisonExpression, const, equalityExpression, functionDeclaration,
ifExpression, integerLiteral, multiplicativeExpression, override, propertyDeclaration, stringLiteral, superExpression,
suspend, unaryExpression, whenExpression */

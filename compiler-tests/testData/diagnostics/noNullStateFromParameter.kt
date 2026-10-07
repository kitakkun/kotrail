// KOTRAIL_CONFIG: rules.noNullStateFromParameter=on
// RENDER_DIAGNOSTICS_FULL_TEXT
import kotlinx.serialization.Serializable

fun execute(path: String) {}
fun probe(path: String): Boolean = path.isNotEmpty()
fun use(text: String?) {}

// Reported: the constructor's null is copied into the object, and every use bails out on it.
class InitializedRecorder(ffmpegPath: String?) {
    private val <!KOTRAIL_NULL_STORED_FROM_PARAMETER!>ffmpegPath<!>: String? = ffmpegPath

    fun record() {
        val path = ffmpegPath ?: return
        execute(path)
    }

    fun length(): Int = ffmpegPath!!.length
}

// Reported: the same null declared in the constructor, used through a dropped safe call and a guard that returns.
class DeclaredRecorder(private val <!KOTRAIL_NULL_STORED_FROM_PARAMETER!>ffmpegPath<!>: String?) {
    fun record() {
        ffmpegPath?.let { execute(it) }
    }

    fun available(): Boolean {
        if (ffmpegPath == null) return false
        return probe(ffmpegPath)
    }
}

// Reported: requireNotNull, checkNotNull, ?: throw and ?: error all leave on null.
class Asserting(private val <!KOTRAIL_NULL_STORED_FROM_PARAMETER!>token<!>: String?) {
    fun first(): String = requireNotNull(token)
    fun second(): String = checkNotNull(token) { "no token" }
    fun third(): String = token ?: throw IllegalStateException("no token")
    fun fourth(): String = token ?: error("no token")
}

// Reported: a guard that exits, then reads the smart cast covers.
class Guarded(private val <!KOTRAIL_NULL_STORED_FROM_PARAMETER!>endpoint<!>: String?) {
    fun connect(): Int {
        if (endpoint == null) return -1
        execute(endpoint)
        return endpoint.length
    }

    fun check(retries: Int) {
        if (endpoint == null || retries > 3) error("cannot connect")
        execute(endpoint)
    }

    fun assertOpen() {
        require(endpoint != null)
        execute(endpoint)
    }
}

// Reported: a default null counts as well.
class Defaulted(private val <!KOTRAIL_NULL_STORED_FROM_PARAMETER!>label<!>: String? = null) {
    fun show() {
        val text = label ?: return
        execute(text)
    }
}

// Reported: a dropped safe call next to an elvis that returns.
class Mixed(private val <!KOTRAIL_NULL_STORED_FROM_PARAMETER!>path<!>: String?) {
    fun start() {
        path?.let { execute(it) }
    }

    fun stop() {
        val current = path ?: return
        execute(current)
    }
}

// Reported: a safe-call chain into an elvis that returns, and null tests that select no branch or one that throws.
class Chained(private val <!KOTRAIL_NULL_STORED_FROM_PARAMETER!>path<!>: String?) {
    fun trimmed(): Int {
        val length = path?.trim()?.length ?: return 0
        return length
    }

    fun run() {
        if (path != null) execute(path)
    }

    fun runOrFail() {
        if (path != null) execute(path) else throw IllegalStateException()
    }
}

// Reported: a var the class only ever assigns a nullable parameter.
class Reconfigured(path: String?) {
    private var <!KOTRAIL_NULL_STORED_FROM_PARAMETER!>path<!>: String? = path

    fun configure(next: String?) {
        path = next
    }

    fun run() {
        val current = path ?: return
        execute(current)
    }
}

// Reported: a bail-out inside a lambda counts the same.
class Deferred(private val <!KOTRAIL_NULL_STORED_FROM_PARAMETER!>path<!>: String?) {
    fun schedule(later: (() -> Unit) -> Unit) {
        later {
            val current = path ?: return@later
            execute(current)
        }
    }
}

// Not reported: the null selects a default.
class Label(private val text: String?) {
    fun render(): String = text ?: "(none)"
}

// Not reported: whether there is a value is the answer.
class Presence(private val value: String?) {
    fun has(): Boolean = value != null
}

// Not reported: passed on as a nullable.
class Forwarder(private val value: String?) {
    fun pass() = use(value)
}

// Not reported: one use bails out, another uses the null as a value.
class HalfBail(private val value: String?) {
    fun strict(): String = value ?: return ""
    fun lenient(): String = "value: $value"
}

// Not reported: public, so callers outside may use the null.
class Exposed(val value: String?) {
    fun strict(): String = value ?: return ""
}

// Not reported: a data class mirrors data.
data class Record(private val value: String?) {
    fun strict(): String = value ?: return ""
}

// Not reported: a serializable class mirrors data.
@Serializable
class Payload(private val value: String?) {
    fun strict(): String = value ?: return ""
}

// Not reported: a value class.
@JvmInline
value class Wrapped(private val value: String?) {
    fun strict(): String = value ?: return ""
}

// Not reported: the class itself sets it to null, so null is a state it manages.
class Clearable(value: String?) {
    private var value: String? = value

    fun strict(): String = value ?: return ""

    fun clear() {
        value = null
    }
}

// Not reported: the initializer transforms the parameter.
class Trimmed(value: String?) {
    private val value: String? = value?.trim()

    fun strict(): String = value ?: return ""
}

// Not reported: nothing reads it.
class Unread(private val value: String?)

// Not reported: the value of a safe call is used.
class Measured(private val value: String?) {
    fun length(): Int? {
        val length = value?.length
        return length
    }
}

// Not reported: both outcomes of the test do work.
class Branching(private val value: String?) {
    fun describe() {
        if (value != null) execute(value) else execute("none")
    }
}

// Not reported: the branch a null selects does work of its own.
class Announced(private val value: String?) {
    fun announce() {
        if (value == null) execute("missing")
    }

    fun strict(): String = value ?: return ""
}

// Not reported: a null does not settle the condition, so it reaches the else branch as a value.
class Conditioned(private val value: String?) {
    fun run(enabled: Boolean) {
        if (value != null && enabled) execute(value) else execute("skipped")
    }
}

// Not reported: the constructor parameter is used as a value elsewhere in the class.
class AlsoDescribed(value: String?) {
    private val value: String? = value
    private val description = "value: $value"

    fun strict(): String = this.value ?: return description
}

// Not reported: suppressed on the constructor property.
class Suppressed(@Suppress("KOTRAIL_NULL_STORED_FROM_PARAMETER") private val value: String?) {
    fun strict(): String = value ?: return ""
}

/* GENERATED_FIR_TAGS: andExpression, assignment, checkNotNullCall, classDeclaration, comparisonExpression, data,
disjunctionExpression, elvisExpression, equalityExpression, functionDeclaration, functionalType, ifExpression,
integerLiteral, lambdaLiteral, localProperty, nullableType, primaryConstructor, propertyDeclaration, safeCall, smartcast,
stringLiteral, thisExpression, value */

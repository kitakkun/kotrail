// KOTRAIL_CONFIG: rules.noPassThroughFunction=on
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kotlin.jvm.JvmStatic

class User(val name: String, val age: Int)

interface Repository {
    fun save(user: User): Boolean
    fun close()
}

class Audit {
    fun record(event: String) {
        println(event)
    }
}

fun store(user: User, force: Boolean): Boolean = user.name.isNotEmpty() && force

fun record(event: String) {
    println(event)
}

// Reported: a block body with one statement is the same thing.
fun <!KOTRAIL_PASS_THROUGH_FUNCTION!>note<!>(event: String) {
    record(event)
}

fun mutableItems(): MutableList<String> = mutableListOf("a")

fun parseImpl(text: String): Int = text.length

private fun hidden(text: String): Int = text.length

class UserService(private val repository: Repository, private val audit: Audit) {
    // Not reported: reaching the callee through a property is encapsulation, and callers of
    // saveUser could not call repository.save themselves.
    fun saveUser(user: User): Boolean = repository.save(user)

    fun log(event: String) {
        audit.record(event)
    }

    // Reported: the same call shape as the function's own signature, under another name.
    fun <!KOTRAIL_PASS_THROUGH_FUNCTION!>persist<!>(user: User, force: Boolean): Boolean {
        return store(user, force)
    }


    // Not reported: an argument the wrapper supplies itself is a default.
    fun saveForced(user: User): Boolean = store(user, true)

    // Not reported: the arguments are reordered into different parameters, which is a decision.
    fun describe(user: User): String = user.name + repository.save(user)

    // Not reported: the receiver is a call result, so the wrapper chooses it.
    fun closeCurrent() = current().close()

    // Not reported: two statements.
    fun saveAndLog(user: User): Boolean {
        audit.record(user.name)
        return repository.save(user)
    }

    private fun current(): Repository = repository
}

// Reported: an extension that only renames a member of its receiver.
fun String.<!KOTRAIL_PASS_THROUGH_FUNCTION!>loud<!>(): String = uppercase()

// Not reported: turning the receiver into an argument is a different call shape.
fun String.size(): Int = parseImpl(this)

// Not reported: the arguments arrive in a different order, which is an adapter.
fun stash(force: Boolean, user: User): Boolean = store(user, force)

// Reported: a vararg spread through.
fun greet(vararg names: String): String = names.joinToString(", ")
fun <!KOTRAIL_PASS_THROUGH_FUNCTION!>hello<!>(vararg names: String): String = greet(*names)

// Not reported: the wrapper narrows the type it exposes.
fun items(): List<String> = mutableItems()

// Not reported: a public function over a private callee is a facade.
fun parse(text: String): Int = hidden(text)

// Not reported: a public bridge to a protected override point; callers cannot reach the callee.
abstract class Plugin {
    fun dispatchStart(name: String): Boolean = onStart(name)
    protected abstract fun onStart(name: String): Boolean

    // Reported: same visibility on both sides.
    protected fun <!KOTRAIL_PASS_THROUGH_FUNCTION!>begin<!>(name: String): Boolean = onStart(name)
}

// Not reported: an internal function over a private callee is a facade too.
internal fun parseInternal(text: String): Int = hidden(text)

// Reported: an internal function over a public callee hides nothing.
internal fun <!KOTRAIL_PASS_THROUGH_FUNCTION!>parsePublic<!>(text: String): Int = parseImpl(text)

// Not reported: a factory over a constructor.
fun user(name: String, age: Int): User = User(name, age)

// Not reported: a default value is something of its own.
fun measure(text: String, trim: Boolean = false): Int = parseImpl(text)

// Not reported: forwarding is what an override is for.
class ClosingRepository(private val delegate: Repository) : Repository {
    override fun save(user: User): Boolean = delegate.save(user)
    override fun close() = delegate.close()
}

// Not reported: an operator has to have this name.
class Counter(private val count: Int) {
    operator fun plus(other: Counter): Counter = add(other)
    private fun add(other: Counter): Counter = Counter(count + other.count)
}

// Not reported: Java-facing adapters.
object Factory {
    @JvmStatic
    fun make(name: String, age: Int): User = user(name, age)
}

// Not reported: inline, whether or not it does anything, is a deliberate shape. Nor is
// `block()`: forwarding to an operator gives syntax a name.
inline fun measured(block: () -> Int): Int = block()
inline fun timed(block: () -> Int): Int = measured(block)
fun invoked(block: () -> Int): Int = block()

// Not reported: `Text` has many more parameters than `title`; exposing one of them is a decision.
@Composable
fun Screen(title: String) {
    Text(title)
}

// Not reported: a preview forwards to the composable it previews; that is what a preview is.
@Preview
@Composable
fun ScreenPreview() = Screen("preview")

// Reported: a composable that is not a preview and only forwards is still a layer with nothing in it.
@Composable
fun <!KOTRAIL_PASS_THROUGH_FUNCTION!>TitledScreen<!>(title: String) = Screen(title)

/* GENERATED_FIR_TAGS: additiveExpression, andExpression, classDeclaration, funWithExtensionReceiver,
functionDeclaration, functionalType, inline, interfaceDeclaration, objectDeclaration, operator, outProjection, override,
primaryConstructor, propertyDeclaration, stringLiteral, thisExpression, vararg */

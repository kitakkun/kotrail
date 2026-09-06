// KOTRAIL_CONFIG: rules.preferExpressionBody=false, rules.preferValueClass=false, rules.namedArgumentsForRepeatedTypes=false
import com.kitakkun.kotrail.preconditions.InferredPreconditions

const val MAX_RETRIES = 5
private const val BASE = 2

fun retry(times: Int) {
    require(times >= 0) { "times must not be negative" }
    println(times)
}

fun window(start: Int, end: Int) {
    require(start < end)
    check(end - start <= 100)
    println(start + end)
}

fun greet(name: String) {
    require(name.isNotBlank())
    println(name)
}

fun opacity(alpha: Float) {
    require(alpha in 0f..1f)
    println(alpha)
}

fun pick(index: Int, items: List<String>) {
    require(index >= 0 && index < items.size)
    println(items[index])
}

fun label(text: String?) {
    requireNotNull(text)
    println(text)
}

fun bounded(count: Int = 3, limit: Int = count * 2) {
    require(count <= limit)
    println(count + limit)
}

// Not a contract: the require runs after other work, so it is not a precondition on the parameters.
fun late(n: Int) {
    println(n)
    require(n > 0)
}

// Not a contract: the condition reads state that is not a parameter.
class Budget(var remaining: Int, val label: String) {
    fun spend(amount: Int) {
        require(amount <= remaining)
        remaining -= amount
    }
}

@JvmInline
value class Percent(val value: Int) {
    init {
        require(value in 0..100)
    }
}

class Port(val number: Int, val service: String) {
    init {
        require(number in 1..65535) { "invalid port $number" }
        require(service.isNotEmpty())
    }
}

// A contract written by hand, as another module's compiled class would carry it.
@InferredPreconditions("delayMillis > 0L")
fun fromLibrary(delayMillis: Long) {
    println(delayMillis)
}

fun literals() {
    retry(3)
    <!PRECONDITION_VIOLATED!>retry(-1)<!>

    window(0, 10)
    <!PRECONDITION_VIOLATED!>window(10, 0)<!>
    <!PRECONDITION_VIOLATED!>window(0, 500)<!>

    greet("kotrail")
    <!PRECONDITION_VIOLATED!>greet("   ")<!>

    opacity(0.5f)
    <!PRECONDITION_VIOLATED!>opacity(1.5f)<!>

    pick(0, listOf("a", "b"))
    <!PRECONDITION_VIOLATED!>pick(2, listOf("a", "b"))<!>
    <!PRECONDITION_VIOLATED!>pick(0, emptyList())<!>

    label("x")
    <!PRECONDITION_VIOLATED!>label(null)<!>

    Percent(50)
    <!PRECONDITION_VIOLATED!>Percent(150)<!>
    Port(8080, "http")
    <!PRECONDITION_VIOLATED!>Port(0, "http")<!>
    <!PRECONDITION_VIOLATED!>Port(80, "")<!>

    fromLibrary(10L)
    <!PRECONDITION_VIOLATED!>fromLibrary(0L)<!>
}

fun folded() {
    // Reported: the value is reached through constants and a local, and folds to -1.
    val attempts = BASE * 2 - MAX_RETRIES
    <!PRECONDITION_VIOLATED!>retry(attempts)<!>

    // Not reported: the same shape, folding to a value that satisfies the contract.
    val allowed = MAX_RETRIES - BASE
    retry(allowed)

    // Reported: a string template built from locals is folded as well.
    val padding = "  "
    <!PRECONDITION_VIOLATED!>greet("$padding ")<!>
    val prefix = "Ms."
    greet("$prefix Doe")

    // Reported: a constant `if` chooses the failing branch.
    val debug = true
    retry(if (debug) 1 else -1)
    <!PRECONDITION_VIOLATED!>retry(if (debug) -1 else 1)<!>

    // Reported: defaults take part. `limit` defaults to `count * 2`, so only the explicit limit fails.
    bounded()
    bounded(count = 4)
    <!PRECONDITION_VIOLATED!>bounded(count = 4, limit = 1)<!>
    <!PRECONDITION_VIOLATED!>bounded(limit = 1)<!>
}

fun unknown(input: Int, flag: Boolean, text: String) {
    // Not reported: the argument is not a compile-time constant.
    retry(input)
    retry(if (flag) 1 else -1)
    greet(text)

    // Not reported: a `var` may change before the call.
    var attempts = -1
    attempts += 2
    retry(attempts)

    // Not reported: conditions that never became contracts.
    late(-1)
    Budget(10, "ads").spend(100)
}

/* GENERATED_FIR_TAGS: additiveExpression, andExpression, assignment, classDeclaration, comparisonExpression, const,
functionDeclaration, ifExpression, init, integerLiteral, lambdaLiteral, localProperty, multiplicativeExpression,
nullableType, primaryConstructor, propertyDeclaration, rangeExpression, smartcast, stringLiteral, value */

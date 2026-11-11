// KOTRAIL_CONFIG: rules.idempotent=on
// RENDER_DIAGNOSTICS_FULL_TEXT
import com.kitakkun.kotrail.idempotency.Idempotent

class Point(var x: Int)

class Counter {
    var count = 0
    var total = 0L
    var label = ""
    var visible = false
    var flags = 0
    var cache: String? = null
    var tags: Set<String> = emptySet()
    var names: List<String> = emptyList()
    var point = Point(0)
    val scores = mutableListOf(0)

    // Each of these changes the state again on a second call.
    @Idempotent
    fun readModifyWrite(delta: Int) {
        <!KOTRAIL_NOT_IDEMPOTENT!>count++<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>--count<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>total += delta<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>total = total * 2<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>count = 1 + count<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>label = "$label!"<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>label += "!"<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>visible = !visible<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>names = names + "x"<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>this.count = this.count - delta<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>point.x++<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>scores[0] += 1<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>scores[0] = scores[0] + delta<!>
    }

    // Each of these leaves the same state however often it runs.
    @Idempotent
    fun settles(value: Int, other: Counter) {
        count = value
        total = value.toLong()
        label = "v$value"
        visible = true
        flags = flags or 4
        flags = flags and 3
        count = maxOf(count, value)
        cache = cache ?: "computed"
        tags = tags + "seen"
        tags = tags - "gone"
        count = other.count + 1
        point = Point(value)
        scores[0] = value
        var local = 0
        local++
        local += value
        val fresh = Point(0)
        fresh.x++
    }

    // An idempotent setter: `field = value` is fine, `field += value` is not.
    var size: Int = 0
        @Idempotent set(value) {
            field = value
        }

    var sum: Int = 0
        @Idempotent set(value) {
            <!KOTRAIL_NOT_IDEMPOTENT!>field += value<!>
        }

    // Not annotated: nothing is checked.
    fun notAnnotated() {
        count++
    }
}

var calls = 0

@Idempotent
fun topLevel() {
    <!KOTRAIL_NOT_IDEMPOTENT!>calls++<!>
}

fun outer() {
    var captured = 0
    var assigned = 0

    // A local variable of an enclosing function is state the local function does not own.
    @Idempotent
    fun local() {
        <!KOTRAIL_NOT_IDEMPOTENT!>captured++<!>
        assigned = 1
    }
    local()
}

// An interface member has no body to check.
interface Repository {
    @Idempotent
    fun refresh()
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, classDeclaration, elvisExpression, functionDeclaration,
incrementDecrementExpression, integerLiteral, interfaceDeclaration, localFunction, localProperty,
multiplicativeExpression, nullableType, primaryConstructor, propertyDeclaration, setter, stringLiteral, thisExpression */

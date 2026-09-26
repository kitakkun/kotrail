// KOTRAIL_CONFIG: rules.preferVal=on
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class Counter(seed: Int) {
    // Reported: a private property nothing assigns to after its initializer.
    private var <!KOTRAIL_PREFER_VAL!>base<!> = seed

    // Not reported: assigned by a member.
    private var count = 0

    // Not reported: not private; anything with access could assign it.
    var label = "counter"
    internal var tag = "t"

    // Not reported: a setter, lateinit, no initializer.
    private var withSetter = 0
        set(value) { field = value * 2 }
    private var later: String
    private lateinit var name: String

    // Reported: delegated, and nothing assigns to it, so the delegate's setter is never called.
    private var <!KOTRAIL_PREFER_VAL!>seed<!> by Box(seed)

    // Not reported: delegated and assigned by a member.
    private var cursor by Box(0)

    init {
        later = "init"
    }

    fun increment() {
        cursor = count
        count += 1
    }

    fun total(items: List<Int>): Int {
        // Reported: a local never reassigned.
        var <!KOTRAIL_PREFER_VAL!>sum<!> = items.sum()

        // Not reported: reassigned, incremented, assigned inside a lambda, assigned in a branch.
        var running = 0
        var ticks = 0
        var seen = 0
        var sign = 1
        items.forEach {
            running += it
            seen = it
        }
        ticks++
        if (sum > 0) sign = -1

        // Not reported: declared without an initializer and assigned later.
        var pending: Int
        pending = base + count

        // Not reported: destructuring.
        var (first, second) = 1 to 2
        first += second

        return sum + running + ticks + seen + sign + pending + withSetter + later.length + name.length + label.length + tag.length
    }
}

// Reported: a private top-level property nothing in the file assigns to.
private var <!KOTRAIL_PREFER_VAL!>fileConstant<!> = 3

// Not reported: assigned by a function of the file.
private var fileCounter = 0

fun bump() {
    fileCounter += fileConstant
}

class Box<T>(private var value: T) {
    operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = value
    operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, next: T) {
        value = next
    }
}

fun delegatedLocals(items: List<Int>) {
    // Reported: a delegated local nothing assigns to.
    var <!KOTRAIL_PREFER_VAL!>total<!> by Box(items.sum())
    // Not reported: assigned inside a lambda of the body.
    var seen by Box(0)
    items.forEach { seen += it }
    println(total + seen)
}

@Composable
fun Toggle(initial: Boolean) {
    // Reported: state that is only read; `val` is the first step, and a value instead of a MutableState the second.
    var <!KOTRAIL_PREFER_VAL!>shown<!> by remember { mutableStateOf(initial) }
    // Not reported: written from a handler.
    var expanded by remember { mutableStateOf(false) }
    val toggle = { expanded = !expanded }
    println("$shown $expanded $toggle")
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, classDeclaration, comparisonExpression, destructuringDeclaration,
functionDeclaration, ifExpression, incrementDecrementExpression, init, integerLiteral, lambdaLiteral, lateinit,
localProperty, multiplicativeExpression, nullableType, operator, primaryConstructor, propertyDeclaration,
propertyDelegate, setter, starProjection, stringLiteral, typeParameter */

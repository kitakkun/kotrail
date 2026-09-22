// KOTRAIL_CONFIG: rules.preferVal=on
class Counter(seed: Int) {
    // Reported: a private property nothing assigns to after its initializer.
    private var <!KOTRAIL_PREFER_VAL!>base<!> = seed

    // Not reported: assigned by a member.
    private var count = 0

    // Not reported: not private; anything with access could assign it.
    var label = "counter"
    internal var tag = "t"

    // Not reported: a setter, a delegate, lateinit, no initializer.
    private var withSetter = 0
        set(value) { field = value * 2 }
    private var later: String
    private lateinit var name: String

    init {
        later = "init"
    }

    fun increment() {
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

/* GENERATED_FIR_TAGS: additiveExpression, assignment, classDeclaration, comparisonExpression, destructuringDeclaration,
functionDeclaration, ifExpression, incrementDecrementExpression, init, integerLiteral, lambdaLiteral, lateinit,
localProperty, multiplicativeExpression, primaryConstructor, propertyDeclaration, setter, stringLiteral */

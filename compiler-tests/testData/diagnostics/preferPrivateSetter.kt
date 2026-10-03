// KOTRAIL_CONFIG: rules.preferPrivateSetter=on

class Counter {
    // Reported: a var returned as is; `var count = 0` with `private set` says the same thing.
    private var _count = 0
    val <!KOTRAIL_PREFER_PRIVATE_SETTER!>count<!>: Int get() = _count

    // Reported: internal is narrower than public, and the type matches.
    internal var _label = "x"
    val <!KOTRAIL_PREFER_PRIVATE_SETTER!>label<!>: String get() = _label

    // Not reported: the exposed type is wider, which a private setter cannot do (the
    // explicit-backing-field rule covers this pair).
    private var _items = mutableListOf<Int>()
    val items: List<Int> get() = _items

    // Not reported: a conversion, not the var itself.
    private var _name = "n"
    val name: String get() = _name.uppercase()

    // Not reported: an initializer copies the value once; the two are not the same state.
    private var _initial = 1
    val initial: Int = _initial

    // Not reported: the backing property is a val (the explicit-backing-field rule's case).
    private val _fixed = 2
    val fixed: Int get() = _fixed

    // Not reported: the setter is already private; nothing to rewrite.
    var done: Boolean = false
        private set

    // Not reported: same visibility on both sides.
    private var _hidden = 0
    private val hidden: Int get() = _hidden

    fun bump() {
        _count++
        _label = "y"
        _items.add(1)
        _name = "m"
        _initial = 2
        _hidden = 3
        done = true
        println("$fixed $hidden")
    }
}

abstract class Base {
    // Not reported: an open property cannot take the rewrite.
    private var _open = 0
    open val open: Int get() = _open
}

interface Countable {
    val total: Int
}

class FinalCountable : Countable {
    // Reported: an override in a final class can take a private setter.
    private var _total = 0
    override val <!KOTRAIL_PREFER_PRIVATE_SETTER!>total<!>: Int get() = _total

    fun bump() { _total++ }
}

open class OpenCountable : Countable {
    // Not reported: a subclass may override it again, and a private setter needs a final property.
    private var _total = 0
    override val total: Int get() = _total

    fun bump() { _total++ }
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, functionDeclaration, getter, incrementDecrementExpression,
integerLiteral, interfaceDeclaration, override, propertyDeclaration, stringLiteral */

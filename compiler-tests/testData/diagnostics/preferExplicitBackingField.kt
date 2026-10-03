// KOTRAIL_CONFIG: rules.preferExplicitBackingField=on
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

abstract class Base {
    // Not reported: open property.
    private val _label = "x"
    open val label: String get() = _label

    abstract val abstractOne: Int
}

class Cases {
    // Reported: getter delegation.
    private val _a = mutableListOf<String>()
    val <!KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD!>a<!>: List<String> get() = _a

    // Reported: initializer delegation through a call on the backing property.
    private val _b = mutableMapOf<String, Int>()
    val <!KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD!>b<!>: Map<String, Int> = _b.toMap()

    // Not reported: the exposing side is a var.
    private val _c = 0
    var c: Int = _c

    // Not reported: the public property does not read the backing one.
    private val _d = 1
    val d: Int = 2

    // Not reported: same visibility on both sides.
    internal val _e = 1
    internal val e: Int get() = _e

    // Reported: internal is narrower than public.
    internal val _f = 1
    val <!KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD!>f<!>: Int get() = _f

    // Not reported: extension property.
    private val _g = 1
    val String.g: Int get() = _g

    // Not reported: already an explicit backing field.
    val h: List<Int>
        field = mutableListOf()

    // Not reported here: a var returned as is belongs to the prefer-private-setter rule, which is off.
    private var _i = 0
    val i: Int get() = _i

    // Reported: a var exposed through a wider type still wants an explicit backing field.
    private var _j = mutableListOf<Int>()
    val <!KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD!>j<!>: List<Int> get() = _j

    fun use() { _d; _c; _i = 1; _j = mutableListOf() }
}

interface Source {
    // Not reported: an abstract declaration.
    val overridden: StateFlow<Int>

    // Not reported: an interface member cannot carry an explicit backing field.
    private val _fromInterface: MutableList<Int> get() = mutableListOf()
    val fromInterface: List<Int> get() = _fromInterface
}

class FinalOverride : Source {
    // Reported: an override in a final class is effectively final.
    private val _overridden = MutableStateFlow(0)
    override val <!KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD!>overridden<!>: StateFlow<Int> = _overridden.asStateFlow()
}

object ObjectOverride : Source {
    // Reported: an object is final too.
    private val _overridden = MutableStateFlow(0)
    override val <!KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD!>overridden<!>: StateFlow<Int> = _overridden.asStateFlow()
}

open class OpenOverride : Source {
    // Not reported: a subclass may override it again, so it is not final.
    private val _overridden = MutableStateFlow(0)
    override val overridden: StateFlow<Int> = _overridden.asStateFlow()
}

open class FinalOverrideInOpenClass : Source {
    // Reported: `final override` is final by itself.
    private val _overridden = MutableStateFlow(0)
    final override val <!KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD!>overridden<!>: StateFlow<Int> = _overridden.asStateFlow()
}

enum class EnumOverride : Source {
    ONE;

    // Not reported: the compiler does not treat an enum class as final.
    private val _overridden = MutableStateFlow(0)
    override val overridden: StateFlow<Int> = _overridden.asStateFlow()
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, enumDeclaration, enumEntry, explicitBackingField,
functionDeclaration, getter, integerLiteral, interfaceDeclaration, objectDeclaration, override, propertyDeclaration,
propertyWithExtensionReceiver, stringLiteral */

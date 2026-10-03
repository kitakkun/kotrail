// KOTRAIL_CONFIG: rules.preferExplicitBackingField=on
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

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

class ByUse {
    // Reported: any private name works when the class only calls on it or assigns to it elsewhere.
    private val counter = MutableStateFlow(0)
    val <!KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD!>count<!>: StateFlow<Int> = counter.asStateFlow()

    // Reported: exposed as is through a getter.
    private val entries = mutableListOf<String>()
    val <!KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD!>entryList<!>: List<String> get() = entries

    // Reported: a var behind a wider type, reassigned and mutated elsewhere.
    private var buffer = mutableListOf<Int>()
    val <!KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD!>bufferView<!>: List<Int> get() = buffer

    // Not reported: a value derived from the property, not the property itself.
    private val source = MutableStateFlow(0)
    val doubled: Flow<Int> = source.map { it * 2 }

    // Not reported: the property's type does not fit under the exposed type, so it cannot be the field.
    private val items = mutableListOf<Int>()
    val isEmpty: Boolean get() = items.isEmpty()

    // Not reported: a property that is not private may be read outside the class.
    internal val internalState = MutableStateFlow(0)
    val fromInternal: StateFlow<Int> = internalState.asStateFlow()

    // Not reported: the class hands the property out elsewhere.
    private val handed = MutableStateFlow(0)
    val handedView: StateFlow<Int> = handed.asStateFlow()

    fun mutate() {
        counter.value = 1
        counter.update { it + 1 }
        entries.add("x")
        entries += "y"
        buffer = mutableListOf()
        buffer.add(1)
        source.value = 2
        items.add(3)
    }

    fun publish(sink: (MutableStateFlow<Int>) -> Unit) {
        sink(handed)
    }
}

internal interface ProbeSource {
    val overridden: StateFlow<Int>
}

internal class ProbeA : ProbeSource {
    // Reported: the plain prefixed pair.
    private val _plain = MutableStateFlow(0)
    val <!KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD!>plain<!>: StateFlow<Int> = _plain.asStateFlow()

    // Reported: an override in a final class.
    private val _overridden = MutableStateFlow(0)
    override val <!KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD!>overridden<!>: StateFlow<Int> = _overridden.asStateFlow()

    // Reported: found by its use, not its name.
    private val mutableNamed = MutableStateFlow(0)
    val <!KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD!>named<!>: StateFlow<Int> = mutableNamed.asStateFlow()

    // Reported: exposed as is under the read-only type.
    private val _upcast = MutableStateFlow(0)
    val <!KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD!>upcast<!>: StateFlow<Int> = _upcast

    // Not reported: snapshot() reads shared as well, so it is not only a backing property.
    private val shared = MutableStateFlow(0)
    val exposed: StateFlow<Int> = shared.asStateFlow()
    fun snapshot() = shared
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, classDeclaration, enumDeclaration, enumEntry,
explicitBackingField, functionDeclaration, functionalType, getter, integerLiteral, interfaceDeclaration, lambdaLiteral,
multiplicativeExpression, objectDeclaration, override, propertyDeclaration, propertyWithExtensionReceiver, stringLiteral */

// LANGUAGE: -ExplicitBackingFields
// KOTRAIL_CONFIG: rules.preferExplicitBackingField=true
// Not reported anywhere: with the language feature off, the rewrite the rule asks for would not
// compile, so the rule stays quiet.
abstract class Base {
    // Not reported: open property.
    private val _label = "x"
    open val label: String get() = _label

    abstract val abstractOne: Int
}

class Cases {
    // Reported: getter delegation.
    private val _a = mutableListOf<String>()
    val a: List<String> get() = _a

    // Reported: initializer delegation through a call on the backing property.
    private val _b = mutableMapOf<String, Int>()
    val b: Map<String, Int> = _b.toMap()

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
    val f: Int get() = _f

    // Not reported: extension property.
    private val _g = 1
    val String.g: Int get() = _g

    fun use() { _d; _c }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, getter, integerLiteral, propertyDeclaration,
propertyWithExtensionReceiver, stringLiteral */

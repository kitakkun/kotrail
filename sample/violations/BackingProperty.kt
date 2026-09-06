// Not part of the sample source set. Copy this file into sample/src/main/kotlin/
// and run ./gradlew :sample:compileKotlin to see Kotrail reject the backing-property idiom.
abstract class Base {
    // Not reported: open property in an open class.
    private val _label = "x"
    open val label: String get() = _label

    // Not reported: abstract property.
    abstract val abstractOne: Int
}

class Cases {
    // Reported: classic getter delegation.
    private val _a = mutableListOf<String>()
    val a: List<String> get() = _a

    // Reported: initializer delegation with a call on the backing property.
    private val _b = mutableMapOf<String, Int>()
    val b: Map<String, Int> = _b.toMap()

    // Not reported: public side is a var (explicit backing fields are val-only).
    private val _c = 0
    var c: Int = _c

    // Not reported: the public property does not read the backing one.
    private val _d = 1
    val d: Int = 2

    // Not reported: internal exposing internal (same visibility).
    internal val _e = 1
    internal val e: Int get() = _e

    // Reported: internal narrower than public.
    internal val _f = 1
    val f: Int get() = _f

    // Not reported: extension property.
    private val _g = 1
    val String.g: Int get() = _g

    fun use() { _d; _c }
}

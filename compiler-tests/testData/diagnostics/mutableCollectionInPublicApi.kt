// KOTRAIL_CONFIG: rules.noMutableCollectionInPublicApi=true
import java.util.ArrayList

// Reported: a public function returning a mutable interface.
fun tags(): <!KOTRAIL_MUTABLE_COLLECTION_IN_PUBLIC_API!>MutableList<String><!> = mutableListOf()

// Reported: a public property typed with a concrete JVM collection (alias to java.util.HashMap).
val cache: <!KOTRAIL_MUTABLE_COLLECTION_IN_PUBLIC_API!>HashMap<String, Int><!> = HashMap()

// Reported: the java.util class written directly through an explicit import.
fun raw(): <!KOTRAIL_MUTABLE_COLLECTION_IN_PUBLIC_API!>ArrayList<Int><!> = ArrayList()

// Reported: a mutable value-parameter type; the caller is invited to hand over shared state.
fun register(names: <!KOTRAIL_MUTABLE_COLLECTION_IN_PUBLIC_API!>MutableSet<String><!>) {
    println(names.size)
}

// Reported: nullable mutable types count as well.
fun maybe(): <!KOTRAIL_MUTABLE_COLLECTION_IN_PUBLIC_API!>MutableMap<String, Int>?<!> = null

// Reported: an implicit return type has no type reference, so the declaration is marked.
<!KOTRAIL_MUTABLE_COLLECTION_IN_PUBLIC_API!>fun inferred() = mutableListOf(1)<!>

open class Registry {
    // Reported: protected is part of the API a subclass sees.
    protected fun entries(): <!KOTRAIL_MUTABLE_COLLECTION_IN_PUBLIC_API!>MutableCollection<String><!> = mutableListOf()

    // Not reported: private and internal members are not public API.
    private val hidden: MutableList<String> = mutableListOf()
    internal fun scratch(): MutableSet<Int> = mutableSetOf()

    // Not reported: a local variable.
    fun compute(): Int {
        val buffer: MutableList<Int> = mutableListOf()
        buffer.add(hidden.size)
        return buffer.size
    }
}

// Reported on the interface; not reported on the override (its signature is fixed).
interface Source {
    fun items(): <!KOTRAIL_MUTABLE_COLLECTION_IN_PUBLIC_API!>MutableList<Int><!>
}

class ListSource : Source {
    override fun items(): MutableList<Int> = mutableListOf()
}

// Not reported: a public member of a private class is not visible outside.
private class Internal {
    fun items(): MutableList<Int> = mutableListOf()
}

// Not reported: only the top-level type is checked.
fun nested(): List<MutableList<Int>> = emptyList()

// Not reported: read-only interfaces.
fun names(): List<String> = emptyList()
val lookup: Map<String, Int> = emptyMap()

// Not reported: constructor parameters and the properties they declare are skipped.
class Box(val items: MutableList<Int>)

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, integerLiteral, interfaceDeclaration, javaFunction,
localProperty, nullableType, override, primaryConstructor, propertyDeclaration */

// KOTRAIL_CONFIG: rules.noNotNullAssertion=on, rules.preferValueClass=on, rules.noNotNullAssertion.exclude=name(*Legacy*) || class(Generated*), rules.preferValueClass.exclude=name(*Id) || file(other.kt)

// For a diagnostic on a declaration, the declaration itself is the subject: the class name is
// what name(...) sees, not an enclosing one.
data class UserId(val value: String)

data class <!KOTRAIL_PREFER_VALUE_CLASS!>Token<!>(val value: String)

fun plain(x: String?): Int = <!KOTRAIL_NOT_NULL_ASSERTION!>x!!<!>.length

// Not reported: the declaration name matches *Legacy*.
fun parseLegacy(x: String?): Int = x!!.length

// Not reported: inside a class matching Generated*, however deep.
class GeneratedMapper {
    fun map(x: String?): Int = x!!.length

    class Inner {
        fun deep(x: String?): Int = x!!.length
    }
}

class Mapper {
    fun map(x: String?): Int = <!KOTRAIL_NOT_NULL_ASSERTION!>x!!<!>.length
}

/* GENERATED_FIR_TAGS: checkNotNullCall, classDeclaration, data, functionDeclaration, nestedClass, nullableType,
primaryConstructor, propertyDeclaration */

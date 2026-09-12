// KOTRAIL_CONFIG: rules.noNotNullAssertion=true, rules.preferValueClass=true, exclude.noNotNullAssertion=name(*Legacy*) || class(Generated*), exclude.preferValueClass=file(excludeByLocation.kt)

// Not reported anywhere in this file: preferValueClass is excluded by file name. The exclusion
// applies to the class declaration itself, which is not among the enclosing declarations.
data class UserId(val value: String)

fun plain(x: String?): Int = <!NOT_NULL_ASSERTION!>x!!<!>.length

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
    fun map(x: String?): Int = <!NOT_NULL_ASSERTION!>x!!<!>.length
}

/* GENERATED_FIR_TAGS: checkNotNullCall, classDeclaration, data, functionDeclaration, nestedClass, nullableType,
primaryConstructor, propertyDeclaration */

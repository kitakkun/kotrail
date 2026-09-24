// KOTRAIL_CONFIG: rules.noNotNullAssertion=on
import javax.annotation.processing.Generated

// Not reported: a declaration marked generated (generated.annotations) is skipped by every rule.
@Generated("a-tool")
class GeneratedHolder(private val name: String?) {
    fun length(): Int = name!!.length
}

// Reported: written by hand.
class Holder(private val name: String?) {
    fun length(): Int = <!KOTRAIL_NOT_NULL_ASSERTION!>name!!<!>.length
}

/* GENERATED_FIR_TAGS: checkNotNullCall, classDeclaration, functionDeclaration, nullableType, primaryConstructor,
propertyDeclaration, stringLiteral */

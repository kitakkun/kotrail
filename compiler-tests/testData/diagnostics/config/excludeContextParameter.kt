// LANGUAGE: +ContextParameters
// KOTRAIL_CONFIG: rules.noNotNullAssertion=true, exclude.noNotNullAssertion=context(custom.Scope)

package custom

class Scope

// Not reported: a context parameter of the excluded type.
context(scope: Scope)
fun scoped(x: String?): Int = x!!.length

// Reported: no context parameter.
fun unscoped(x: String?): Int = <!KOTRAIL_NOT_NULL_ASSERTION!>x!!<!>.length

// Reported: a context parameter of another type.
context(other: String)
fun otherwise(x: String?): Int = <!KOTRAIL_NOT_NULL_ASSERTION!>x!!<!>.length

/* GENERATED_FIR_TAGS: checkNotNullCall, classDeclaration, functionDeclaration, functionDeclarationWithContext,
nullableType */

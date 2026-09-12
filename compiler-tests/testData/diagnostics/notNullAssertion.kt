// KOTRAIL_CONFIG: rules.noNotNullAssertion=true
class User(val name: String?)

fun load(): User? = null

fun reported(user: User?, other: User?, third: User?, names: Map<String, String>) {
    // Reported: plain `!!`.
    val forced = <!KOTRAIL_NOT_NULL_ASSERTION!>user!!<!>

    // Reported: `!!` at the start of a chain.
    val name = <!KOTRAIL_NOT_NULL_ASSERTION!>other!!<!>.name

    // Reported: `!!` on a call result.
    val loaded = <!KOTRAIL_NOT_NULL_ASSERTION!>load()!!<!>

    // Reported: `!!` on an indexed access.
    val first = <!KOTRAIL_NOT_NULL_ASSERTION!>names["first"]!!<!>

    // Reported: nested chain with two assertions, each reported once.
    val length = <!KOTRAIL_NOT_NULL_ASSERTION!><!KOTRAIL_NOT_NULL_ASSERTION!>third!!<!>.name!!<!>.length
    print("$forced $name $loaded $first $length")
}

fun quiet(user: User?, other: User?): Int {
    // Not reported: safe call and elvis.
    val length = user?.name?.length ?: 0

    // Not reported: early return instead of asserting.
    val present = user ?: return length

    // Not reported: a smart cast after a null check.
    if (present.name != null) {
        return present.name.length
    }

    // Not reported: an explicit precondition.
    val checked = requireNotNull(other?.name)
    val alsoChecked = checkNotNull(other.name)
    return length + checked.length + alsoChecked.length
}

/* GENERATED_FIR_TAGS: additiveExpression, checkNotNullCall, classDeclaration, elvisExpression, equalityExpression,
functionDeclaration, ifExpression, integerLiteral, localProperty, nullableType, primaryConstructor, propertyDeclaration,
safeCall, smartcast, stringLiteral */

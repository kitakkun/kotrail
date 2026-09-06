data class User(val id: Long, val name: String)

private val store = mutableListOf<User>()

// Reported: side effect, then the input comes straight back.
fun <!PASS_THROUGH_RETURN!>cache<!>(user: User): User {
    store.add(user)
    return user
}

// Reported: expression body that is the parameter.
fun <!PASS_THROUGH_RETURN!>identity<!>(x: Int) = x

// Reported: the receiver is returned on every path.
fun String.<!PASS_THROUGH_RETURN!>logged<!>(): String {
    println(this)
    return this
}

// Reported: both paths return the same parameter.
fun <!PASS_THROUGH_RETURN!>validate<!>(user: User): User {
    if (user.name.isEmpty()) {
        println("empty name")
        return user
    }
    return user
}

// Not reported: chooses between inputs.
fun pick(a: Int, b: Int, first: Boolean): Int = if (first) a else b

// Not reported: one path returns something else.
fun trimmed(s: String): String {
    if (s.isEmpty()) return s
    return s.trim()
}

// Not reported: a new value is produced.
fun renamed(user: User, name: String): User = user.copy(name = name)

// Not reported: nullable fallback.
fun orDefault(x: Int?, default: Int): Int = x ?: default

// Not reported: returns inside lambdas target the lambda, not the function.
fun names(users: List<User>): List<String> = users.map <!PREFER_FUNCTION_REFERENCE!>{ u -> return@map u.name }<!>

// Not reported: overrides, operators, and inline helpers are exempt.
interface Normalizer {
    fun normalize(user: User): User
}

class NoOpNormalizer : Normalizer {
    override fun normalize(user: User): User = user
}

operator fun User.unaryPlus(): User = this

inline fun <T> T.tap(block: (T) -> Unit): T {
    block(this)
    return this
}

// Not reported: no value is returned.
fun log(user: User) {
    println(user)
}

/* GENERATED_FIR_TAGS: classDeclaration, data, elvisExpression, funWithExtensionReceiver, functionDeclaration,
functionalType, ifExpression, inline, interfaceDeclaration, lambdaLiteral, nullableType, operator, override,
primaryConstructor, propertyDeclaration, stringLiteral, thisExpression, typeParameter */

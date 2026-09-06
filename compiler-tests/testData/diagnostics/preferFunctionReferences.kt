data class User(val id: Long, val name: String)

fun transform(user: User): String = user.name
fun pair(a: Int, b: Int): Int = a + b
fun greet(name: String, punctuation: String = "!"): String = "Hello $name$punctuation"
fun joinAll(vararg parts: String): String = parts.joinToString()
fun <T> wrap(value: T): List<T> = listOf(value)
suspend fun fetch(id: Long): String = "$id"
fun String.shout(): String = uppercase()

fun overloaded(x: Int): Int = x + 1
fun overloaded(x: String): String = x + "!"

object Formatter {
    fun format(user: User): String = user.name
}

class Repository {
    val users: List<User> = emptyList()
    fun save(user: User) {}
    fun load(id: Long): User = User(id, "")

    // Reported: member call on the implicit receiver.
    fun saveAll(list: List<User>) = list.forEach <!PREFER_FUNCTION_REFERENCE!>{ save(it) }<!>

    // Reported: member call on an explicit `this`.
    fun loadAll(ids: List<Long>) = ids.map <!PREFER_FUNCTION_REFERENCE!>{ this.load(it) }<!>
}

fun examples(users: List<User>, repository: Repository, ints: List<Int>) {
    // Reported: top-level function, parameter forwarded.
    users.map <!PREFER_FUNCTION_REFERENCE!>{ transform(it) }<!>

    // Reported: property read on the lambda parameter.
    users.map <!PREFER_FUNCTION_REFERENCE!>{ it.name }<!>

    // Reported: extension call on the lambda parameter.
    listOf("a").map <!PREFER_FUNCTION_REFERENCE!>{ it.shout() }<!>

    // Reported: two parameters forwarded in order.
    ints.zip(ints) <!PREFER_FUNCTION_REFERENCE!>{ a, b -> pair(a, b) }<!>

    // Reported: bound reference on a val.
    users.forEach <!PREFER_FUNCTION_REFERENCE!>{ repository.save(it) }<!>

    // Reported: bound reference on an object.
    users.map <!PREFER_FUNCTION_REFERENCE!>{ Formatter.format(it) }<!>

    // Not reported: arguments are transformed or reordered.
    users.map { transform(it).length }
    ints.zip(ints) { a, b -> pair(b, a) }

    // Not reported: a default argument is omitted, or an extra argument is passed.
    listOf("a").map { greet(it) }
    listOf("a").map { greet(it, "?") }

    // Not reported: vararg, generic, or overloaded callee.
    listOf("a").map { joinAll(it) }
    users.map { wrap(it) }
    ints.map { overloaded(it) }

    // Not reported: more than one statement.
    users.forEach {
        println(it)
        repository.save(it)
    }

    // Reported: a member call on the parameter, even with a named parameter.
    val loader: (Long) -> String = <!PREFER_FUNCTION_REFERENCE!>{ id -> id.toString() }<!>
    loader(1)

    // Not reported: the receiver is a fresh expression, not a stable value.
    users.forEach { Repository().save(it) }

    // Not reported: lambda with a receiver type.
    val block: StringBuilder.() -> Unit = { append("x") }
    StringBuilder().block()
}

suspend fun suspending(ids: List<Long>) {
    // Reported: a suspend lambda forwarding to a suspend function.
    val loader: suspend (Long) -> String = <!PREFER_FUNCTION_REFERENCE!>{ fetch(it) }<!>
    loader(1)

    // Reported: inside a suspend function, an ordinary lambda forwarding to an ordinary function.
    ids.map <!PREFER_FUNCTION_REFERENCE!>{ fetchLater(it) }<!>
}

fun fetchLater(id: Long): String = "$id"

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, data, flexibleType, funWithExtensionReceiver,
functionDeclaration, functionalType, javaFunction, lambdaLiteral, localProperty, nullableType, objectDeclaration,
outProjection, primaryConstructor, propertyDeclaration, stringLiteral, suspend, thisExpression, typeParameter,
typeWithExtension, vararg */

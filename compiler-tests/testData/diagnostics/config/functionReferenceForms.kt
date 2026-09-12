// KOTRAIL_CONFIG: rules.preferFunctionReferences=true, preferFunctionReferences.forms=topLevel,bound
// Type-qualified references (`File::readText`, `User::name`) are not requested here, so lambdas
// that would only become that form are left alone.
data class User(val id: Long, val name: String)

fun transform(user: User): String = user.name
fun String.shout(): String = uppercase()

class Repository {
    fun save(user: User) {}
}

fun examples(users: List<User>, repository: Repository) {
    // Reported: topLevel.
    users.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ transform(it) }<!>

    // Reported: bound.
    users.forEach <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ repository.save(it) }<!>

    // Not reported: these would be typeQualified, which is switched off.
    users.map { it.name }
    listOf("a").map { it.shout() }
}

/* GENERATED_FIR_TAGS: classDeclaration, data, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration, stringLiteral */

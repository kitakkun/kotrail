// KOTRAIL_CONFIG: rules.nameSaysType=on, rules.nameSaysType.scope=all, rules.nameSaysType.exempt=cfg,on*, rules.nameSaysType.valueTypes=kotlin.Unit
// With scope=all, top-level properties, locals, local functions' and lambdas' parameters are
// checked too; the exempt list replaces the default one, so `cfg` passes and `other` does not.
// The value types are narrowed to Unit, so an Int asks for its word as well, except in a constant.

class UserRepository
class User
class AppConfig

// Reported: a top-level property.
val <!KOTRAIL_NAME_HIDES_TYPE!>repoInstance<!> = UserRepository()

// Not reported: the name carries a word of the type.
val userRepository = UserRepository()

// Not reported: exempt by the configured list.
val cfg = AppConfig()

class NodeActionHandler {
    companion object {
        // Not reported: a companion's instance of its own class, read as NodeActionHandler.Default.
        val Default = NodeActionHandler()
    }
}

// Reported: only a companion's instances of its own class are exempt, a top-level one is not.
val <!KOTRAIL_NAME_HIDES_TYPE!>LongClick<!> = NodeActionHandler()

// Not reported: a constant is named for its role, whatever its type.
const val retryLimit = 3

// Reported: an Int is no longer a value type.
val <!KOTRAIL_NAME_HIDES_TYPE!>retries<!> = 3

// Reported: `other` is no longer exempt.
fun same(<!KOTRAIL_NAME_HIDES_TYPE!>other<!>: User, user: User): Boolean = other == user

fun main() {
    // Reported: a local.
    val <!KOTRAIL_NAME_HIDES_TYPE!>repo<!> = UserRepository()
    val users = listOf(User())

    // Reported: a lambda parameter.
    users.forEach { <!KOTRAIL_NAME_HIDES_TYPE!>u<!> -> println(u) }

    // Not reported: the implicit `it`, a loop variable that says its type.
    users.forEach { println(it) }
    for (user in users) println(user)

    // Reported: a local function's parameter.
    fun helper(<!KOTRAIL_NAME_HIDES_TYPE!>r<!>: UserRepository) = r

    // Not reported: a catch parameter.
    try {
        helper(repo)
    } catch (ex: IllegalStateException) {
        println(ex)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, equalityExpression, forLoop, functionDeclaration, lambdaLiteral, localFunction,
localProperty, propertyDeclaration, tryExpression */

// KOTRAIL_CONFIG: rules.preferFunctionReferences=on, rules.preferFunctionReferences.ignoredFunctions=app.ui.transform,app.ui.Repository.save*,app.ui.User.name,java.io.File.getAbsolutePath
// Lambdas that forward to a listed function or property are left alone; a Java getter read as a
// property is listed by the getter's name, the one the reference would use.
package app.ui

import java.io.File

data class User(val id: Long, val name: String)

fun transform(user: User): String = user.name
fun describe(user: User): String = user.name

class Repository {
    fun save(user: User) {}
    fun saveAll(users: List<User>) {}
    fun load(id: Long): User = User(id, "")
}

fun examples(users: List<User>, groups: List<List<User>>, ids: List<Long>, repository: Repository, files: List<File>) {
    // Not reported: listed by name, by a glob, as a property, and as a Java getter.
    users.map { transform(it) }
    users.forEach { repository.save(it) }
    groups.forEach { repository.saveAll(it) }
    users.map { it.name }
    files.map { it.absolutePath }

    // Reported: not listed.
    users.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ describe(it) }<!>
    ids.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ repository.load(it) }<!>
    users.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.id }<!>
    files.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.name }<!>
}

/* GENERATED_FIR_TAGS: classDeclaration, data, flexibleType, functionDeclaration, javaProperty, lambdaLiteral,
primaryConstructor, propertyDeclaration, stringLiteral */
